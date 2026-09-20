package com.petgyebu.telo.budget.domain;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 사용자의 한 달치 예산 기간(F-FZUVLV).
 *
 * <p>기간 경계는 KST 기준의 매월 1일~말일이다. 날짜 환산이 필요한 곳은
 * {@code com.petgyebu.telo.common.time.AppZone}을 쓴다.
 *
 * <p>목표 금액이 둘인 이유: 사용률·캐릭터 상태는 {@code targetAmount}(현재 값)로 계산하고,
 * 절약 보상은 {@code targetAmountSnapshot}(기간 시작 값)으로 판정한다. 기간 중 목표를 올려
 * 보상을 쉽게 타는 것을 막는다(docs/09-db-design.md 4.1절).
 *
 * <p>DB에 {@code UNIQUE (user_id, period_start)}가 있어 같은 달 기간이 둘 생기지 않는다.
 * s9 배치가 중복 실행돼도 두 번째 삽입은 제약 위반으로 막힌다.
 */
@Entity
@Table(name = "budget_periods")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BudgetPeriod {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false)
	private LocalDate periodStart;

	@Column(nullable = false)
	private LocalDate periodEnd;

	@Column(nullable = false)
	private Long targetAmount;

	/** 기간 시작 시점 목표. 절약 보상 판정 기준이라 갱신 경로를 매핑 단계에서 막는다. */
	@Column(nullable = false, updatable = false)
	private Long targetAmountSnapshot;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private BudgetPeriodStatus status;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다({@code User.joinedAt}과 같은 이유). */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 기간을 개시한다. 생성 시점의 목표 금액이 곧 스냅샷 값이 된다.
	 *
	 * @param now 생성·수정 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public BudgetPeriod(
			User user,
			LocalDate periodStart,
			LocalDate periodEnd,
			long targetAmount,
			OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.periodStart = Objects.requireNonNull(periodStart, "periodStart");
		this.periodEnd = Objects.requireNonNull(periodEnd, "periodEnd");
		this.targetAmount = targetAmount;
		this.targetAmountSnapshot = targetAmount;
		this.status = BudgetPeriodStatus.ACTIVE;
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}
}
