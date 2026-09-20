package com.petgyebu.telo.account.domain;

/**
 * 가계부 집계에서 제외된 계좌의 목록 노출 방식(docs/09-db-design.md 3.1절).
 *
 * <p>{@link #BADGE}는 "제외" 배지를 달아 목록에 계속 보여주고, {@link #HIDDEN}은 목록에서
 * 감춘다. {@code includedInBudget = false}일 때만 의미가 있다.
 */
public enum DisplayMode {
	BADGE,
	HIDDEN
}
