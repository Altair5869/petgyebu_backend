package com.petgyebu.telo.budget.domain;

/**
 * 예산 기간의 상태.
 *
 * <p>{@link #ACTIVE}는 진행 중인 기간, {@link #CLOSED}는 s9 배치가 마감한 기간이다
 * (docs/09-db-design.md 4.1절).
 */
public enum BudgetPeriodStatus {
	ACTIVE,
	CLOSED
}
