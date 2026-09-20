package com.petgyebu.telo.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.budget.domain.BudgetPeriod;
import com.petgyebu.telo.budget.domain.BudgetPeriodStatus;
import com.petgyebu.telo.budget.domain.StatusCode;
import com.petgyebu.telo.budget.domain.StatusThreshold;
import com.petgyebu.telo.budget.repository.BudgetPeriodRepository;
import com.petgyebu.telo.budget.repository.StatusThresholdRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.math.BigDecimal;
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
 * {@code budget_periods}·{@code status_thresholds} 스키마가 실제 PostgreSQL에서 설계대로
 * 동작하는지 본다({@code UserSchemaTest}와 같은 방식).
 *
 * <p>여기서 보는 것은 애플리케이션 코드가 아니라 DB 제약이 보장해야 하는 것들이다.
 * <b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("budget_periods·status_thresholds 스키마 제약 검증")
class BudgetSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	private static final LocalDate SEPTEMBER_START = LocalDate.of(2026, 9, 1);
	private static final LocalDate SEPTEMBER_END = LocalDate.of(2026, 9, 30);
	private static final LocalDate OCTOBER_START = LocalDate.of(2026, 10, 1);
	private static final LocalDate OCTOBER_END = LocalDate.of(2026, 10, 31);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BudgetPeriodRepository budgetPeriodRepository;

	@Autowired
	private StatusThresholdRepository statusThresholdRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("같은 사용자·같은 달 예산 기간은 두 번 저장할 수 없다 — s9 배치 중복 실행 방어")
	void duplicatePeriodForSameMonthIsRejected() {
		User user = givenUser("budget-dup-1");
		budgetPeriodRepository.saveAndFlush(septemberPeriod(user, 300_000L));

		assertThatThrownBy(() -> budgetPeriodRepository.saveAndFlush(septemberPeriod(user, 500_000L)))
				.as("UNIQUE (user_id, period_start)가 걸려 있지 않다. s9 배치가 중복 실행되면 "
						+ "같은 달 예산 기간이 둘 생긴다")
				.isInstanceOf(DataIntegrityViolationException.class)
				// 어떤 제약이 걸었는지까지 본다. 이름을 확인하지 않으면 NOT NULL이나 CHECK 위반으로
				// 실패해도 이 테스트가 초록이 된다.
				.rootCause()
				.hasMessageContaining("uq_budget_periods_user_id_period_start");
	}

	@Test
	@DisplayName("같은 사용자가 서로 다른 달의 예산 기간을 함께 가질 수 있다 — s9 배치의 다음 기간 생성")
	void consecutiveMonthPeriodsForSameUserAreAllowed() {
		User user = givenUser("budget-next-month-1");
		budgetPeriodRepository.saveAndFlush(septemberPeriod(user, 300_000L));

		BudgetPeriod october = budgetPeriodRepository.saveAndFlush(
				period(user, OCTOBER_START, OCTOBER_END, 300_000L));

		// 유니크가 (user_id, period_start)가 아니라 (user_id)에 걸리면 여기서 깨진다. 그 스키마는
		// F-FZUVLV action "기간 종료 시 다음 기간 자동 생성"(s9 배치)을 통째로 막는다.
		assertThat(october.getId()).isNotNull();
		assertThat(count(new JdbcTemplate(dataSource),
				"SELECT count(*) FROM budget_periods WHERE user_id = ?", user.getId()))
				.as("한 사용자가 두 달치 예산 기간을 가질 수 없다. s9 배치가 다음 달 기간을 만들지 못한다")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("사용자가 다르면 같은 달 예산 기간을 각각 가질 수 있다")
	void samePeriodStartForAnotherUserIsAllowed() {
		budgetPeriodRepository.saveAndFlush(septemberPeriod(givenUser("budget-cross-1"), 300_000L));
		BudgetPeriod other =
				budgetPeriodRepository.saveAndFlush(septemberPeriod(givenUser("budget-cross-2"), 300_000L));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	@DisplayName("같은 기간에 같은 상태 구간을 두 번 저장할 수 없다")
	void duplicateStatusCodeInOnePeriodIsRejected() {
		BudgetPeriod period =
				budgetPeriodRepository.saveAndFlush(septemberPeriod(givenUser("threshold-dup-1"), 300_000L));
		statusThresholdRepository.saveAndFlush(threshold(period, StatusCode.REST, "0.00", "40.00", (short) 1));

		assertThatThrownBy(() -> statusThresholdRepository.saveAndFlush(
				threshold(period, StatusCode.REST, "0.00", "50.00", (short) 1)))
				.as("UNIQUE (budget_period_id, status_code)가 걸려 있지 않다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_status_thresholds_period_status");
	}

	@Test
	@DisplayName("OVER_BUDGET 구간은 end_rate가 null이어도 저장된다 — 상한 없음")
	void overBudgetThresholdHasNoEndRate() {
		BudgetPeriod period =
				budgetPeriodRepository.saveAndFlush(septemberPeriod(givenUser("threshold-open-1"), 300_000L));

		StatusThreshold saved = statusThresholdRepository.saveAndFlush(
				threshold(period, StatusCode.OVER_BUDGET, "100.00", null, (short) 6));

		assertThat(saved.getId()).isNotNull();
		assertThat(statusThresholdRepository.findById(saved.getId()))
				.get()
				.extracting(StatusThreshold::getEndRate)
				.isNull();
	}

	@Test
	@DisplayName("목표 금액이 0 이하인 예산 기간은 저장할 수 없다")
	void nonPositiveTargetAmountIsRejected() {
		User user = givenUser("budget-amount-1");

		assertThatThrownBy(() -> budgetPeriodRepository.saveAndFlush(septemberPeriod(user, 0L)))
				.as("CHECK (target_amount > 0)이 없다")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("사용자를 지우면 예산 기간과 구간도 함께 지워진다 — ON DELETE CASCADE")
	void deletingUserCascadesToPeriodsAndThresholds() {
		User user = givenUser("budget-cascade-1");
		BudgetPeriod period = budgetPeriodRepository.saveAndFlush(septemberPeriod(user, 300_000L));
		statusThresholdRepository.saveAndFlush(threshold(period, StatusCode.REST, "0.00", "40.00", (short) 1));
		statusThresholdRepository.saveAndFlush(
				threshold(period, StatusCode.OVER_BUDGET, "100.00", null, (short) 6));

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		assertThat(count(jdbc, "SELECT count(*) FROM budget_periods WHERE user_id = ?", user.getId()))
				.isEqualTo(1);
		assertThat(count(jdbc, "SELECT count(*) FROM status_thresholds WHERE budget_period_id = ?",
				period.getId()))
				.isEqualTo(2);

		userRepository.delete(user);
		userRepository.flush();

		assertThat(count(jdbc, "SELECT count(*) FROM budget_periods WHERE user_id = ?", user.getId()))
				.as("budget_periods FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
		assertThat(count(jdbc, "SELECT count(*) FROM status_thresholds WHERE budget_period_id = ?",
				period.getId()))
				.as("status_thresholds FK에 ON DELETE CASCADE가 없다")
				.isZero();
	}

	@Test
	@DisplayName("새 예산 기간은 ACTIVE로 시작하고 스냅샷이 목표 금액과 같다")
	void newPeriodStartsActiveWithSnapshot() {
		BudgetPeriod saved =
				budgetPeriodRepository.saveAndFlush(septemberPeriod(givenUser("budget-init-1"), 300_000L));

		assertThat(budgetPeriodRepository.findById(saved.getId()))
				.get()
				.satisfies(period -> {
					assertThat(period.getStatus()).isEqualTo(BudgetPeriodStatus.ACTIVE);
					assertThat(period.getTargetAmount()).isEqualTo(300_000L);
					assertThat(period.getTargetAmountSnapshot()).isEqualTo(300_000L);
					assertThat(period.getPeriodStart()).isEqualTo(SEPTEMBER_START);
					assertThat(period.getPeriodEnd()).isEqualTo(SEPTEMBER_END);
				});
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private BudgetPeriod septemberPeriod(User user, long targetAmount) {
		return period(user, SEPTEMBER_START, SEPTEMBER_END, targetAmount);
	}

	private BudgetPeriod period(User user, LocalDate start, LocalDate end, long targetAmount) {
		return new BudgetPeriod(user, start, end, targetAmount, OffsetDateTime.now(AppZone.clock()));
	}

	private StatusThreshold threshold(
			BudgetPeriod period, StatusCode statusCode, String startRate, String endRate, short sortOrder) {
		return new StatusThreshold(
				period,
				statusCode,
				new BigDecimal(startRate),
				endRate == null ? null : new BigDecimal(endRate),
				sortOrder,
				OffsetDateTime.now(AppZone.clock()));
	}

	private int count(JdbcTemplate jdbc, String sql, Long id) {
		Integer count = jdbc.queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
