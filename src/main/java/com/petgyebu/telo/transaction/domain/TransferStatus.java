package com.petgyebu.telo.transaction.domain;

/**
 * 계좌 간 이체 판정 상태(docs/09-db-design.md 3.4절).
 *
 * <p>{@link #NONE} 이체가 아님, {@link #PENDING_CONFIRM} 후보로 잡혔고 사용자 확인 대기,
 * {@link #AUTO_LINKED} 자동 연결됨, {@link #USER_CONFIRMED} 사용자가 이체로 확인,
 * {@link #UNLINKED} 자동 연결을 사용자가 해제.
 *
 * <p>{@link #PENDING_CONFIRM}만 모아 보는 화면이 있어 DB에 부분 인덱스가 걸려 있다.
 */
public enum TransferStatus {
	NONE,
	PENDING_CONFIRM,
	AUTO_LINKED,
	USER_CONFIRMED,
	UNLINKED
}
