package com.petgyebu.telo.account.domain;

/**
 * 계좌 거래 조회 동의 상태(docs/09-db-design.md 3.1절).
 *
 * <p>{@link #ACTIVE}는 동의가 유효한 상태, {@link #EXPIRED}는 동의 만료, {@link #REVOKED}는
 * 사용자가 연결을 해제한 상태다.
 */
public enum ConsentStatus {
	ACTIVE,
	EXPIRED,
	REVOKED
}
