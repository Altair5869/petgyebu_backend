package com.petgyebu.telo.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.account.domain.Account;
import com.petgyebu.telo.account.repository.AccountRepository;
import com.petgyebu.telo.budget.domain.BudgetPeriod;
import com.petgyebu.telo.budget.repository.BudgetPeriodRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 비정규화한 {@code user_id}가 부모의 소유자와 어긋나는 것을 DB가 막는지 본다.
 *
 * <p>세 테이블이 부모를 통해 이미 알 수 있는 {@code user_id}를 따로 들고 있다 —
 * {@code transactions}(부모 {@code accounts}), {@code reward_grants}·{@code push_logs}
 * (부모 {@code budget_periods}). 가로지르는 관심사라 도메인별 스키마 테스트가 아니라
 * 여기에 둔다.
 *
 * <p>전에는 코드 규율("거래 저장은 반드시 계좌 조회를 거친 경로로만")로만 막고 있었다.
 * 어긋나도 예외도 경고도 나지 않아, 사용자 A의 집계에 B의 데이터가 섞여도 금액이
 * 이상하다는 신고를 받기 전까지 알 수 없었다. 복합 FK로 물리적으로 불가능하게 바꿨다.
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("소유자 정합성 — 비정규화한 user_id")
class OwnerIntegrityTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	private static final short UNCLASSIFIED = 99;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private BudgetPeriodRepository budgetPeriodRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("거래는 계좌 주인과 다른 user_id로 저장할 수 없다")
	void transactionCannotClaimAnotherUsersAccount() {
		User owner = givenUser("owner-txn");
		User stranger = givenUser("stranger-txn");
		Account account = givenAccount(owner);

		assertThatCode(() -> insertTransaction(owner.getId(), account.getId(), "own-ok"))
				.as("계좌 주인이 자기 계좌로 거래를 남기는 정상 경로가 막혔다")
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> insertTransaction(stranger.getId(), account.getId(), "own-bad"))
				.as("남의 계좌로 거래를 남길 수 있다. 그 사용자의 예산 사용률에 섞여 들어간다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("fk_transactions_account_owner");
	}

	@Test
	@DisplayName("보상은 예산 기간 주인과 다른 user_id로 저장할 수 없다")
	void rewardGrantCannotClaimAnotherUsersPeriod() {
		User owner = givenUser("owner-reward");
		User stranger = givenUser("stranger-reward");
		BudgetPeriod period = givenPeriod(owner);

		assertThatCode(() -> insertGrant(owner.getId(), period.getId()))
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> insertGrant(stranger.getId(), period.getId()))
				.as("남의 예산 기간으로 크레딧을 받을 수 있다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("fk_reward_grants_period_owner");
	}

	@Test
	@DisplayName("푸시 로그는 예산 기간 주인과 다른 user_id로 저장할 수 없다")
	void pushLogCannotClaimAnotherUsersPeriod() {
		User owner = givenUser("owner-push");
		User stranger = givenUser("stranger-push");
		BudgetPeriod period = givenPeriod(owner);

		assertThatCode(() -> insertPushLog(owner.getId(), period.getId()))
				.doesNotThrowAnyException();

		// 유니크 (budget_period_id, threshold_type)에 걸리지 않도록 임계값을 달리한다.
		assertThatThrownBy(() -> insertPushLog(stranger.getId(), period.getId(), "OVER_BUDGET"))
				.as("남의 예산 기간으로 발송 기록을 남길 수 있다. 확인율 KPI가 오염된다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("fk_push_logs_period_owner");
	}

	// ── 헬퍼 ────────────────────────────────────────────────
	// 전부 raw SQL이다. 엔티티 생성자는 user와 account/period를 따로 받으므로 JPA로도
	// 어긋난 조합을 만들 수 있지만, 여기서 보려는 것은 "DB가 막는가"이므로 영속성
	// 컨텍스트를 거치지 않는 가장 낮은 경로로 찌른다.

	private void insertTransaction(Long userId, Long accountId, String codefId) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO transactions (user_id, account_id, codef_transaction_id, transacted_at,"
						+ " amount, txn_type, category_id, initial_classification_source,"
						+ " transfer_status, refund_status)"
						+ " VALUES (?, ?, ?, now(), 1000, 'EXPENSE', ?, 'UNCLASSIFIED', 'NONE', 'NONE')",
				userId, accountId, codefId, UNCLASSIFIED);
	}

	private void insertGrant(Long userId, Long periodId) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO reward_grants (user_id, budget_period_id, condition_type,"
						+ " credit_amount, period_expense_total)"
						+ " VALUES (?, ?, 'WITHIN_TARGET', 100, 400000)",
				userId, periodId);
	}

	private void insertPushLog(Long userId, Long periodId) {
		insertPushLog(userId, periodId, "STRONG_WARNING");
	}

	private void insertPushLog(Long userId, Long periodId, String thresholdType) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO push_logs (user_id, budget_period_id, threshold_type, sent_at)"
						+ " VALUES (?, ?, ?, now())",
				userId, periodId, thresholdType);
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(new User(
				AuthProvider.KAKAO,
				providerUserId + "-" + System.nanoTime(),
				null,
				OffsetDateTime.now(AppZone.clock())));
	}

	private Account givenAccount(User user) {
		return accountRepository.saveAndFlush(new Account(
				user, "004", "connected-" + System.nanoTime(), "110-****-1234",
				null, null, OffsetDateTime.now(AppZone.clock())));
	}

	private BudgetPeriod givenPeriod(User user) {
		return budgetPeriodRepository.saveAndFlush(new BudgetPeriod(
				user,
				LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 31),
				500_000L,
				OffsetDateTime.now(AppZone.clock())));
	}
}
