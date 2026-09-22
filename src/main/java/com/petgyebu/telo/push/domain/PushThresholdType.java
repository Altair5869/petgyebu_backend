package com.petgyebu.telo.push.domain;

/**
 * 푸시를 발송하는 사용률 임계값 두 가지(F-VZFPVW).
 *
 * <p>{@code com.petgyebu.telo.budget.domain.StatusCode}에도 같은 이름의 상수가 있지만 그쪽은
 * 캐릭터 상태 6종 전부다. 푸시는 그중 둘에서만 나가고 DB의
 * {@code ck_push_logs_threshold_type}도 둘만 허용하므로, 나머지 넷이 들어올 수 없도록 별도
 * 열거형을 둔다.
 */
public enum PushThresholdType {

	/** 사용률 90% 진입. */
	STRONG_WARNING,

	/** 사용률 100% 초과. */
	OVER_BUDGET
}
