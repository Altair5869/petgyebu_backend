package com.petgyebu.telo.budget.domain;

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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 예산 기간의 사용률 구간 한 칸(F-FZUVLV).
 *
 * <p>{@code endRate}는 {@link StatusCode#OVER_BUDGET}에서만 null이다(상한 없음).
 *
 * <p>구간 검증(0% 고정, 오름차순, 중복·공백 없음, 6단계 전부 존재)과 오른쪽 닫힘
 * {@code (시작, 끝]} 경계값 판정은 DB가 아니라 애플리케이션이 맡는다
 * (docs/09-db-design.md 4.2절). 그 로직은 T-025 범위다.
 */
@Entity
@Table(name = "status_thresholds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatusThreshold {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "budget_period_id", nullable = false)
	private BudgetPeriod budgetPeriod;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StatusCode statusCode;

	@Column(nullable = false, precision = 5, scale = 2)
	private BigDecimal startRate;

	/** OVER_BUDGET만 null이다. */
	@Column(precision = 5, scale = 2)
	private BigDecimal endRate;

	@Column(nullable = false)
	private short sortOrder;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	public StatusThreshold(
			BudgetPeriod budgetPeriod,
			StatusCode statusCode,
			BigDecimal startRate,
			BigDecimal endRate,
			short sortOrder,
			OffsetDateTime updatedAt) {
		this.budgetPeriod = Objects.requireNonNull(budgetPeriod, "budgetPeriod");
		this.statusCode = Objects.requireNonNull(statusCode, "statusCode");
		this.startRate = Objects.requireNonNull(startRate, "startRate");
		this.endRate = endRate;
		this.sortOrder = sortOrder;
		this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
	}
}
