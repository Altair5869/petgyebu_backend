package com.petgyebu.telo.user;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.ConsentType;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.domain.UserConsent;
import com.petgyebu.telo.user.repository.UserConsentRepository;
import com.petgyebu.telo.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code users}·{@code user_consents} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지 본다.
 *
 * <p>{@code PostgresMigrationTest}와 같은 방식으로 기본 프로필(Flyway + {@code ddl-auto: validate})
 * 그대로 띄운다. 여기서 검증하는 세 가지는 애플리케이션 코드가 아니라 DB 제약이 보장해야 하는
 * 것들이라 H2나 모킹으로는 확인할 수 없다. <b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("users·user_consents 스키마 제약 검증")
class UserSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserConsentRepository userConsentRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("(provider, provider_user_id)가 같은 계정은 두 번 저장할 수 없다")
	void duplicateProviderIdentityIsRejected() {
		userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, "dup-1", "a@example.com", OffsetDateTime.now()));

		assertThatThrownBy(() -> userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, "dup-1", "b@example.com", OffsetDateTime.now())))
				.as("UNIQUE (provider, provider_user_id)가 걸려 있지 않다")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("공급자가 다르면 같은 provider_user_id를 써도 별도 계정이다")
	void sameProviderUserIdOnAnotherProviderIsAllowed() {
		userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, "cross-1", null, OffsetDateTime.now()));
		User apple = userRepository.saveAndFlush(
				new User(AuthProvider.APPLE, "cross-1", null, OffsetDateTime.now()));

		assertThat(apple.getId()).isNotNull();
	}

	@Test
	@DisplayName("이메일이 null인 계정을 저장할 수 있다 — 애플 이메일 가리기")
	void userWithoutEmailIsStored() {
		User saved = userRepository.saveAndFlush(
				new User(AuthProvider.APPLE, "no-email-1", null, OffsetDateTime.now()));

		assertThat(saved.getId()).isNotNull();
		assertThat(userRepository.findById(saved.getId()))
				.get()
				.extracting(User::getEmail)
				.isNull();
	}

	@Test
	@DisplayName("사용자를 지우면 동의 기록도 함께 지워진다 — ON DELETE CASCADE")
	void deletingUserCascadesToConsents() {
		User user = userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, "cascade-1", null, OffsetDateTime.now()));
		userConsentRepository.saveAndFlush(new UserConsent(
				user, ConsentType.TERMS_OF_SERVICE, "v1.0", OffsetDateTime.now()));
		userConsentRepository.saveAndFlush(new UserConsent(
				user, ConsentType.PRIVACY_POLICY, "v1.0", OffsetDateTime.now()));

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		assertThat(countConsents(jdbc, user.getId())).isEqualTo(2);

		userRepository.delete(user);
		userRepository.flush();

		assertThat(countConsents(jdbc, user.getId()))
				.as("FK에 ON DELETE CASCADE가 없다. T-005 탈퇴 API가 이 동작에 의존한다")
				.isZero();
	}

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지")
	void designedIndexesExist() {
		// 가입 후 24시간·7일 코호트 집계용.
		assertIndex("users", "ix_users_joined_at", "joined_at");
		// 최신 동의 버전 조회용. 정렬 방향까지 설계의 일부다.
		assertIndex("user_consents", "ix_user_consents_user_id_type_consented_at",
				"user_id, consent_type, consented_at DESC");
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다 — 빠진 것도 더 붙은 것도 없다")
	void nullabilityMatchesDesign() {
		assertNullability("users", Map.ofEntries(
				entry("id", "NO"),
				entry("provider", "NO"),
				entry("provider_user_id", "NO"),
				entry("email", "YES"),  // 애플 이메일 가리기·카카오 선택 동의로 null일 수 있다
				entry("character_type", "YES"),  // 캐릭터 선택 전에는 null이다
				entry("character_changed_at", "YES"),  // 한 번도 바꾸지 않았으면 null이다
				entry("joined_at", "NO"),
				entry("last_login_at", "YES")));  // 한 번도 로그인하지 않았으면 null이다

		assertNullability("user_consents", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("consent_type", "NO"),
				entry("consent_version", "NO"),
				entry("consented_at", "NO")));
	}

	/**
	 * 테이블의 NOT NULL 구성이 설계와 정확히 일치하는지 본다.
	 *
	 * <p>{@code NOT NULL}은 인덱스와 달리 틀린 답을 낸다. 그런데 마이그레이션에서 지워도
	 * 아무것도 깨지지 않는다. Hibernate의 {@code ddl-auto: validate}는 nullability를 보지
	 * 않고, 엔티티의 {@code @Column(nullable = false)}는 DDL 생성용이라 Flyway가 테이블을
	 * 만드는 이 구성에서는 아무 일도 하지 않는다. 제약 테스트도 늘 제대로 된 값을 넣으므로
	 * 빈 값을 막는 규칙이 있든 없든 결과가 같다. 빌드는 초록인데 null이 들어온다.
	 *
	 * <p>컬럼을 하나씩 보지 않고 테이블 전체의 {@code column_name → is_nullable} 맵을
	 * 통째로 비교한다. 그래야 양방향으로 잡힌다. {@code NOT NULL}이 사라지는 것뿐 아니라
	 * 원래 null을 허용하던 컬럼에 {@code NOT NULL}이 붙는 것, 컬럼이 늘거나 없어지는 것도
	 * 함께 걸린다.
	 *
	 * @param expected 컬럼명 → {@code "NO"}(NOT NULL) 또는 {@code "YES"}(null 허용)
	 */
	private void assertNullability(String tableName, Map<String, String> expected) {
		List<Map<String, Object>> columns = new JdbcTemplate(dataSource).queryForList(
				"SELECT column_name, is_nullable FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = ?",
				tableName);

		Map<String, String> actual = columns.stream().collect(Collectors.toMap(
				column -> (String) column.get("column_name"),
				column -> (String) column.get("is_nullable")));

		assertThat(actual)
				.as("%s 테이블의 NOT NULL 구성이 설계와 다르다", tableName)
				.containsExactlyInAnyOrderEntriesOf(expected);
	}

	/**
	 * 인덱스가 설계대로 존재하는지 본다.
	 *
	 * <p>인덱스는 틀린 답이 아니라 느린 답을 낸다. {@code CREATE INDEX}를 통째로 지워도
	 * {@code ddl-auto: validate}도, 제약 테스트도, 빌드도 전부 통과한다. 그래서 스키마를
	 * 직접 조회해 못 박는 단언이 따로 필요하다.
	 *
	 * <p>이름만 보지 않고 {@code indexdef}의 대상 열 구성까지 본다. 이름을 유지한 채 열만
	 * 바꾸는 변이는 이름 단언을 통과하기 때문이다.
	 *
	 * @param expectedColumns {@code indexdef} 괄호 안에 그대로 나타나야 하는 열 목록
	 */
	private void assertIndex(String tableName, String indexName, String expectedColumns) {
		List<String> definitions = new JdbcTemplate(dataSource).queryForList(
				"SELECT indexdef FROM pg_indexes "
						+ "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
				String.class, tableName, indexName);

		assertThat(definitions)
				.as("%s 테이블에 인덱스 %s가 없다", tableName, indexName)
				.hasSize(1);
		assertThat(definitions.get(0))
				.as("인덱스 %s의 대상 열 구성이 설계와 다르다", indexName)
				.endsWith("(" + expectedColumns + ")");
	}

	private int countConsents(JdbcTemplate jdbc, Long userId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM user_consents WHERE user_id = ?", Integer.class, userId);
		return count == null ? 0 : count;
	}
}
