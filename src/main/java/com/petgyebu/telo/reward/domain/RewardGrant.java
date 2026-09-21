package com.petgyebu.telo.reward.domain;

import com.petgyebu.telo.budget.domain.BudgetPeriod;
import com.petgyebu.telo.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 절약 보상 지급 기록 한 건(F-EZZFNU).
 *
 * <p>DB에 {@code UNIQUE (user_id, budget_period_id, condition_type)}가 있어 <b>동일 조건을 두
 * 번 지급할 수 없다.</b> 판정 배치가 재실행돼도 안전하다. 키에 {@code condition_type}이 들어
 * 있으므로 <b>조건이 다르면 같은 사용자·같은 기간에도 두 행이 남는다</b> — 두 조건을 모두
 * 충족한 달은 행 2개, 합계 300크레딧이다.
 *
 * <p>판정 근거 두 컬럼({@code periodExpenseTotal}·{@code previousPeriodExpenseTotal})은 나중에
 * "왜 보상을 못 받았는지" 확인할 때 쓴다. 직전 기간이 없는 첫 기간은 두 번째 값이 null이다.
 *
 * <p>조건 판정과 크레딧 지급은 T-043 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "reward_grants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardGrant {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** 판정 대상 기간. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "budget_period_id", nullable = false)
	private BudgetPeriod budgetPeriod;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private RewardConditionType conditionType;

	/** 조건별 100 또는 200. 금액 자체는 T-043이 정한다. */
	@Column(nullable = false)
	private long creditAmount;

	/** 판정 근거: 해당 기간 지출 합계. */
	@Column(nullable = false)
	private long periodExpenseTotal;

	/** 판정 근거: 직전 기간 지출 합계. 첫 기간은 null이다. */
	private Long previousPeriodExpenseTotal;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다. */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime grantedAt;

	/**
	 * 보상 지급을 기록한다.
	 *
	 * @param previousPeriodExpenseTotal 직전 기간 지출 합계. 첫 기간이면 null
	 * @param now 지급 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public RewardGrant(
			User user,
			BudgetPeriod budgetPeriod,
			RewardConditionType conditionType,
			long creditAmount,
			long periodExpenseTotal,
			Long previousPeriodExpenseTotal,
			OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.budgetPeriod = Objects.requireNonNull(budgetPeriod, "budgetPeriod");
		this.conditionType = Objects.requireNonNull(conditionType, "conditionType");
		this.creditAmount = creditAmount;
		this.periodExpenseTotal = periodExpenseTotal;
		this.previousPeriodExpenseTotal = previousPeriodExpenseTotal;
		this.grantedAt = Objects.requireNonNull(now, "now");
	}
}
