package com.petgyebu.telo.sync;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.petgyebu.telo.account.domain.Account;
import com.petgyebu.telo.account.repository.AccountRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.sync.domain.SyncAttempt;
import com.petgyebu.telo.sync.domain.SyncResult;
import com.petgyebu.telo.sync.domain.TriggerType;
import com.petgyebu.telo.sync.repository.SyncAttemptRepository;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
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
 * {@code sync_attempts} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지 본다
 * ({@code TransactionSchemaTest}와 같은 방식).
 *
 * <p>이 테이블만 다른 것이 둘 있다. 둘 다 여기서 못 박는다.
 * <ol>
 *   <li>계좌 FK가 {@code ON DELETE SET NULL}이다 — CASCADE도 NO ACTION도 아니다</li>
 *   <li>append-only라 {@code updated_at}이 없다</li>
 * </ol>
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("sync_attempts 스키마 제약 검증")
class SyncAttemptSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private SyncAttemptRepository syncAttemptRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("계좌를 지워도 시도 기록은 남고 account_id만 null이 된다 — ON DELETE SET NULL")
	void deletingAccountSetsAccountIdToNull() {
		Account account = givenAccount("sync-setnull-1");
		SyncAttempt saved = syncAttemptRepository.saveAndFlush(attempt(account, TriggerType.SCHEDULED));

		new JdbcTemplate(dataSource).update("DELETE FROM accounts WHERE id = ?", account.getId());

		// CASCADE로 바뀌면 행이 통째로 사라져 수집 성공률 KPI의 모수가 계좌 해제로 줄어든다.
		Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap(
				"SELECT account_id, bank_code, user_id FROM sync_attempts WHERE id = ?",
				saved.getId());

		assertThat(row.get("account_id"))
				.as("계좌를 지웠는데 account_id가 null이 되지 않았다. FK가 SET NULL이 아니다")
				.isNull();
		assertThat(row.get("bank_code"))
				.as("계좌가 사라진 뒤 어느 은행이었는지 알 수 없게 됐다")
				.isEqualTo("004");
		assertThat(row.get("user_id"))
				.as("사용자 연결까지 끊겼다")
				.isEqualTo(account.getUser().getId());
	}

	@Test
	@DisplayName("사용자를 지우면 시도 기록도 함께 지워진다 — ON DELETE CASCADE")
	void deletingUserCascadesToSyncAttempts() {
		User user = givenUser("sync-cascade-1");
		Account account = givenAccount(user);
		syncAttemptRepository.saveAndFlush(attempt(account, TriggerType.SCHEDULED));

		new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", user.getId());

		assertThat(count("SELECT count(*) FROM sync_attempts WHERE user_id = ?", user.getId()))
				.as("users FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
	}

	@Test
	@DisplayName("계좌 없이도 기록을 남길 수 있다 — account_id는 NULL 허용이다")
	void attemptWithoutAccountIsAllowed() {
		SyncAttempt saved = syncAttemptRepository.saveAndFlush(new SyncAttempt(
				givenUser("sync-null-account-1"), null, "004", TriggerType.MANUAL,
				OffsetDateTime.now(AppZone.clock()), SyncResult.FAILURE, "계좌 조회 실패", (short) 0));

		assertThat(syncAttemptRepository.findById(saved.getId()))
				.get()
				.satisfies(found -> {
					assertThat(found.getAccount()).isNull();
					assertThat(found.getBankCode()).isEqualTo("004");
					assertThat(found.getFailureReason()).isEqualTo("계좌 조회 실패");
				});
	}

	@Test
	@DisplayName("정의되지 않은 trigger_type·result는 저장할 수 없다 — CHECK 제약")
	void undefinedEnumValuesAreRejected() {
		Account account = givenAccount("sync-check-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sync_attempts (user_id, account_id, bank_code, trigger_type, "
						+ "attempted_at, result) VALUES (?, ?, ?, ?, now(), ?)",
				account.getUser().getId(), account.getId(), "004", "RETRY", "SUCCESS"))
				.as("CHECK (trigger_type IN (...))가 없다. KPI 모수 구분이 무너진다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_sync_attempts_trigger_type");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO sync_attempts (user_id, account_id, bank_code, trigger_type, "
						+ "attempted_at, result) VALUES (?, ?, ?, ?, now(), ?)",
				account.getUser().getId(), account.getId(), "004", "SCHEDULED", "PARTIAL"))
				.as("CHECK (result IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_sync_attempts_result");
	}

	@Test
	@DisplayName("명세에 있는 trigger_type·result 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다")
	void definedEnumValuesAreAccepted() {
		// 거부 케이스만 보면 CHECK 목록에서 값을 하나 빼도 통과한다. 'MANUAL'이 빠지면 사용자가
		// 직접 누른 동기화를 기록할 수 없고, 'FAILURE'가 빠지면 실패 기록 자체가 사라져 수집
		// 성공률이 늘 100%가 된다.
		Account account = givenAccount("sync-check-ok-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		jdbc.update(
				"INSERT INTO sync_attempts (user_id, account_id, bank_code, trigger_type, "
						+ "attempted_at, result, failure_reason) VALUES (?, ?, ?, ?, now(), ?, ?)",
				account.getUser().getId(), account.getId(), "004", "MANUAL", "FAILURE", "타임아웃");
		jdbc.update(
				"INSERT INTO sync_attempts (user_id, account_id, bank_code, trigger_type, "
						+ "attempted_at, result) VALUES (?, ?, ?, ?, now(), ?)",
				account.getUser().getId(), account.getId(), "004", "SCHEDULED", "SUCCESS");

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT trigger_type, result FROM sync_attempts WHERE account_id = ? "
						+ "ORDER BY trigger_type",
				account.getId());

		assertThat(rows)
				.as("명세에 있는 trigger_type·result 조합이 저장되지 않았다")
				.extracting("trigger_type", "result")
				.containsExactly(tuple("MANUAL", "FAILURE"), tuple("SCHEDULED", "SUCCESS"));
	}

	@Test
	@DisplayName("retry_count를 주지 않으면 0이 들어간다 — DEFAULT 0")
	void retryCountDefaultsToZero() {
		Account account = givenAccount("sync-default-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		jdbc.update(
				"INSERT INTO sync_attempts (user_id, account_id, bank_code, trigger_type, "
						+ "attempted_at, result) VALUES (?, ?, ?, ?, now(), ?)",
				account.getUser().getId(), account.getId(), "004", "SCHEDULED", "SUCCESS");

		Integer retryCount = jdbc.queryForObject(
				"SELECT CAST(retry_count AS integer) FROM sync_attempts WHERE account_id = ?",
				Integer.class, account.getId());

		assertThat(retryCount)
				.as("retry_count의 DEFAULT가 0이 아니다")
				.isZero();
	}

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향까지")
	void designedIndexesExist() {
		// 계좌별 최근 시도 조회. DESC가 빠지면 indexdef에서 토큰이 사라져 여기서 깨진다.
		assertIndex("sync_attempts", "ix_sync_attempts_account_attempted_at", "btree",
				"account_id, attempted_at DESC", null);
		// 수집 성공률 95% 지표 집계용.
		assertIndex("sync_attempts", "ix_sync_attempts_trigger_type_attempted_at", "btree",
				"trigger_type, attempted_at", null);
	}

	@Test
	@DisplayName("컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지")
	void columnTypesMatchDesign() {
		// bank_code가 정수형이면 '004'의 앞자리 0이 날아간다. retry_count는 int4로 바뀌어도
		// Hibernate validate가 문제 삼지 않는다(T-013).
		assertColumnType("retry_count", "int2", null);
		assertColumnType("attempted_at", "timestamptz", null);
		assertColumnType("account_id", "int8", null);

		// VARCHAR는 길이까지 본다. udt_name은 길이가 달라도 'varchar'라 길이 변이를 못 잡는다.
		assertColumnType("bank_code", "varchar", 10);
		assertColumnType("result", "varchar", 10);
		assertColumnType("failure_reason", "varchar", 500);
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다 — updated_at이 없는 것까지")
	void nullabilityMatchesDesign() {
		// 맵을 통째로 비교하므로 append-only 테이블에 updated_at이 더 붙는 것도 여기서 걸린다.
		assertNullability("sync_attempts", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("account_id", "YES"),  // 계좌 해제 시 SET NULL이 된다
				entry("bank_code", "NO"),
				entry("trigger_type", "NO"),
				entry("attempted_at", "NO"),
				entry("result", "NO"),
				entry("failure_reason", "YES"),  // 성공이면 사유가 없다
				entry("retry_count", "NO")));
	}

	/**
	 * {@code TransactionSchemaTest.assertColumnType}과 같은 취지다. VARCHAR는 길이까지 본다.
	 *
	 * @param expectedLength VARCHAR의 길이. 문자열 타입이 아니면 null
	 */
	private void assertColumnType(
			String columnName, String expectedUdtName, Integer expectedLength) {
		Map<String, Object> column = new JdbcTemplate(dataSource).queryForMap(
				"SELECT udt_name, character_maximum_length FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = 'sync_attempts' "
						+ "AND column_name = ?",
				columnName);

		assertThat(column.get("udt_name"))
				.as("sync_attempts.%s의 타입이 설계와 다르다", columnName)
				.isEqualTo(expectedUdtName);
		assertThat(column.get("character_maximum_length"))
				.as("sync_attempts.%s의 길이가 설계와 다르다", columnName)
				.isEqualTo(expectedLength);
	}

	/** {@code TransactionSchemaTest.assertNullability}와 같은 취지다. */
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
	 * 인덱스가 설계대로 존재하는지 본다. 종류와 정렬 방향까지 본다
	 * ({@code TransactionSchemaTest.assertIndex}와 같은 취지. 여기에는 부분 인덱스가 없어 조건 인자는 늘 null이다).
	 */
	private void assertIndex(
			String tableName, String indexName, String method, String expectedColumns,
			String expectedPredicate) {
		List<String> definitions = new JdbcTemplate(dataSource).queryForList(
				"SELECT indexdef FROM pg_indexes "
						+ "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
				String.class, tableName, indexName);

		assertThat(definitions)
				.as("%s 테이블에 인덱스 %s가 없다", tableName, indexName)
				.hasSize(1);

		String expectedTail = "USING " + method + " (" + expectedColumns + ")"
				+ (expectedPredicate == null ? "" : " WHERE " + expectedPredicate);
		assertThat(definitions.get(0))
				.as("인덱스 %s의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다", indexName)
				.endsWith(expectedTail);
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private Account givenAccount(String providerUserId) {
		return givenAccount(givenUser(providerUserId));
	}

	private Account givenAccount(User user) {
		return accountRepository.saveAndFlush(new Account(
				user,
				"004",
				"connected-id-" + user.getId(),
				"110-****-1234",
				null,
				null,
				OffsetDateTime.now(AppZone.clock())));
	}

	private SyncAttempt attempt(Account account, TriggerType triggerType) {
		return new SyncAttempt(
				account.getUser(),
				account,
				account.getBankCode(),
				triggerType,
				OffsetDateTime.now(AppZone.clock()),
				SyncResult.SUCCESS,
				null,
				(short) 0);
	}

	private int count(String sql, Long id) {
		Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
