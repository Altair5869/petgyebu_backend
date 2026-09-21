package com.petgyebu.telo.reward.domain;

/**
 * 절약 보상 지급 조건(F-EZZFNU).
 *
 * <p>두 조건은 독립이다. 둘 다 충족하면 {@link RewardGrant} 행이 2개 생기고 크레딧이 합산된다.
 * 조건별 지급 사유를 보여줘야 해서 한 행에 합치지 않는다.
 *
 * <p>DB의 {@code ck_reward_grants_condition_type}과 값이 같아야 한다.
 */
public enum RewardConditionType {

	/** 기간 지출이 목표 금액 이내. */
	WITHIN_TARGET,

	/** 직전 기간 대비 10% 이상 절약. 첫 기간은 비교 대상이 없어 판정하지 않는다. */
	SAVED_10_PERCENT
}
