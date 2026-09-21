package com.petgyebu.telo.transaction.domain;

/**
 * 이체 연결의 근거(docs/09-db-design.md 3.5절).
 *
 * <p>{@link #AUTO}는 자동 판정으로 연결된 것, {@link #USER_CONFIRMED}는 사용자가 확인한 것이다.
 */
public enum LinkStatus {
	AUTO,
	USER_CONFIRMED
}
