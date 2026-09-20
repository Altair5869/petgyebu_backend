package com.petgyebu.telo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.account.domain.Account;
import com.petgyebu.telo.account.domain.ConsentStatus;
import com.petgyebu.telo.account.domain.DisplayMode;
import com.petgyebu.telo.account.repository.AccountRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
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
 * {@code accounts} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지 본다
 * ({@code BudgetSchemaTest}와 같은 방식).
 *
 * <p>여기서 보는 것은 애플리케이션 코드가 아니라 DB 제약이 보장해야 하는 것들이다.
 * <b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("accounts 스키마 제약 검증")
class AccountSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	/** 국민은행 조직코드. 앞자리가 0이라 정수로 저장하면 값이 망가진다. */
	private static final String BANK_CODE_WITH_LEADING_ZERO = "004";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("앞자리 0이 있는 은행 조직코드가 그대로 보존된다 — bank_code는 VARCHAR다")
	void bankCodeKeepsLeadingZero() {
		Account saved = accountRepository.saveAndFlush(
				account(givenUser("account-bankcode-1"), BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		assertThat(accountRepository.findById(saved.getId()))
				.get()
				.extracting(Account::getBankCode)
				.as("엔티티로 읽은 bank_code에서 앞자리 0이 사라졌다")
				.isEqualTo("004");

		// JPA 캐시를 우회해 DB에 실제로 들어간 값을 본다. 컬럼이 정수형이면 '4'가 돌아온다.
		String stored = new JdbcTemplate(dataSource).queryForObject(
				"SELECT bank_code FROM accounts WHERE id = ?", String.class, saved.getId());
		assertThat(stored)
				.as("DB에 저장된 bank_code에서 앞자리 0이 사라졌다. 컬럼이 VARCHAR가 아닐 가능성이 높다")
				.isEqualTo("004");

		// 컬럼 타입 자체를 못 박는다. 나중에 누가 정수로 바꾸면 여기서 깨진다.
		String dataType = new JdbcTemplate(dataSource).queryForObject(
				"SELECT data_type FROM information_schema.columns "
						+ "WHERE table_name = 'accounts' AND column_name = 'bank_code'",
				String.class);
		assertThat(dataType)
				.as("bank_code 컬럼이 문자열 타입이 아니다")
				.isEqualTo("character varying");
	}

	@Test
	@DisplayName("같은 사용자·같은 은행·같은 마스킹 번호 계좌는 두 번 연결할 수 없다")
	void duplicateAccountForSameUserIsRejected() {
		User user = givenUser("account-dup-1");
		accountRepository.saveAndFlush(account(user, BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		assertThatThrownBy(() -> accountRepository.saveAndFlush(
				account(user, BANK_CODE_WITH_LEADING_ZERO, "110-****-1234")))
				.as("UNIQUE (user_id, bank_code, masked_account_no)가 걸려 있지 않다. "
						+ "같은 계좌를 두 번 연결할 수 있다")
				.isInstanceOf(DataIntegrityViolationException.class)
				// 어떤 제약이 걸었는지까지 본다. 이름을 확인하지 않으면 NOT NULL이나 CHECK 위반으로
				// 실패해도 이 테스트가 초록이 된다.
				.rootCause()
				.hasMessageContaining("uq_accounts_user_bank_masked_no");
	}

	@Test
	@DisplayName("같은 사용자가 같은 은행의 다른 계좌를 함께 가질 수 있다 — 동일 은행 복수 계좌")
	void sameBankDifferentMaskedNoForSameUserIsAllowed() {
		User user = givenUser("account-same-bank-1");
		accountRepository.saveAndFlush(account(user, BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		Account second = accountRepository.saveAndFlush(
				account(user, BANK_CODE_WITH_LEADING_ZERO, "110-****-5678"));

		// 유니크가 (user_id)나 (user_id, bank_code)로 좁혀지면 여기서 깨진다. 그 스키마는
		// "동일 은행 복수 계좌 연결"(docs/09-db-design.md 3.1절)을 통째로 막는다.
		assertThat(second.getId()).isNotNull();
		assertThat(count("SELECT count(*) FROM accounts WHERE user_id = ?", user.getId()))
				.as("한 사용자가 같은 은행의 계좌를 둘 가질 수 없다")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("같은 사용자가 다른 은행의 같은 마스킹 번호 계좌를 함께 가질 수 있다")
	void sameMaskedNoAtAnotherBankForSameUserIsAllowed() {
		User user = givenUser("account-cross-bank-1");
		accountRepository.saveAndFlush(account(user, BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		// 유니크가 (user_id, masked_account_no)로 좁혀지면 여기서 깨진다. 마스킹 번호는
		// 은행이 다르면 겹칠 수 있다.
		Account other = accountRepository.saveAndFlush(account(user, "088", "110-****-1234"));

		assertThat(other.getId()).isNotNull();
		assertThat(count("SELECT count(*) FROM accounts WHERE user_id = ?", user.getId()))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("사용자가 다르면 같은 계좌 식별값을 각각 가질 수 있다")
	void sameAccountForAnotherUserIsAllowed() {
		accountRepository.saveAndFlush(
				account(givenUser("account-user-1"), BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		Account other = accountRepository.saveAndFlush(
				account(givenUser("account-user-2"), BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	@DisplayName("정의되지 않은 consent_status·display_mode는 저장할 수 없다 — CHECK 제약")
	void undefinedEnumValuesAreRejected() {
		// 엔티티 쪽은 열거형이라 잘못된 값이 들어갈 수 없다. CHECK가 실제로 붙어 있는지는
		// SQL로 직접 넣어봐야 드러난다.
		User user = givenUser("account-check-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO accounts (user_id, bank_code, codef_connected_id, masked_account_no, "
						+ "consent_status) VALUES (?, ?, ?, ?, ?)",
				user.getId(), BANK_CODE_WITH_LEADING_ZERO, "connected-id", "110-****-9999", "PENDING"))
				.as("CHECK (consent_status IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_accounts_consent_status");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO accounts (user_id, bank_code, codef_connected_id, masked_account_no, "
						+ "consent_status, display_mode) VALUES (?, ?, ?, ?, ?, ?)",
				user.getId(), BANK_CODE_WITH_LEADING_ZERO, "connected-id", "110-****-9999",
				"ACTIVE", "COLLAPSED"))
				.as("CHECK (display_mode IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_accounts_display_mode");
	}

	@Test
	@DisplayName("사용자를 지우면 연결된 계좌도 함께 지워진다 — ON DELETE CASCADE")
	void deletingUserCascadesToAccounts() {
		User user = givenUser("account-cascade-1");
		accountRepository.saveAndFlush(account(user, BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));
		assertThat(count("SELECT count(*) FROM accounts WHERE user_id = ?", user.getId())).isEqualTo(1);

		userRepository.delete(user);
		userRepository.flush();

		assertThat(count("SELECT count(*) FROM accounts WHERE user_id = ?", user.getId()))
				.as("accounts FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
	}

	@Test
	@DisplayName("새로 연결한 계좌는 ACTIVE·집계 포함·BADGE·재인증 불필요로 시작한다")
	void newAccountStartsWithExpectedDefaults() {
		Account saved = accountRepository.saveAndFlush(
				account(givenUser("account-init-1"), BANK_CODE_WITH_LEADING_ZERO, "110-****-1234"));

		assertThat(accountRepository.findById(saved.getId()))
				.get()
				.satisfies(found -> {
					assertThat(found.getConsentStatus()).isEqualTo(ConsentStatus.ACTIVE);
					assertThat(found.getDisplayMode()).isEqualTo(DisplayMode.BADGE);
					assertThat(found.isIncludedInBudget()).isTrue();
					assertThat(found.isReauthRequired()).isFalse();
					assertThat(found.getLastSyncedAt())
							.as("한 번도 동기화하지 않은 계좌의 last_synced_at은 null이어야 한다")
							.isNull();
					assertThat(found.getAccountName()).isNull();
				});
	}

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 이름과 대상 열 구성까지")
	void designedIndexesExist() {
		// 계좌 목록 조회용.
		assertIndex("accounts", "ix_accounts_user_id", "user_id");
		// 스케줄러가 동기화 대상 계좌를 고를 때.
		assertIndex("accounts", "ix_accounts_consent_status_last_synced_at",
				"consent_status, last_synced_at");
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

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private Account account(User user, String bankCode, String maskedAccountNo) {
		return new Account(
				user,
				bankCode,
				"connected-id-" + maskedAccountNo,
				maskedAccountNo,
				null,
				null,
				OffsetDateTime.now(AppZone.clock()));
	}

	private int count(String sql, Long id) {
		Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
