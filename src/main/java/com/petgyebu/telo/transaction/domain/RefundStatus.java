package com.petgyebu.telo.transaction.domain;

/**
 * 취소·환불 상태(docs/09-db-design.md 3.4절).
 *
 * <p>원거래({@link #ORIGINAL})와 환불({@link #REFUND})을 각각 행으로 저장하고
 * {@code linked_refund_transaction_id}로 잇는다. 순액은 집계 시점에 계산한다.
 */
public enum RefundStatus {
	NONE,
	ORIGINAL,
	REFUND
}
