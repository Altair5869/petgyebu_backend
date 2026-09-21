package com.petgyebu.telo.transaction.domain;

/**
 * 거래 방향(docs/09-db-design.md 3.4절).
 *
 * <p>{@code amount}는 언제나 양수다. 수입인지 지출인지는 이 값이 정한다.
 */
public enum TxnType {
	INCOME,
	EXPENSE
}
