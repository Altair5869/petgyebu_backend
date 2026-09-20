package com.petgyebu.telo.budget.domain;

/**
 * 사용률 구간에 대응하는 캐릭터 상태 6종.
 *
 * <p>선언 순서가 {@code sort_order} 1~6과 같다. 기본 구간 값과 상태 묘사 문구는 이 열거형이
 * 아니라 T-025의 상수·서비스가 다룬다. 상태 묘사 문구는 테이블로 만들지 않는다
 * (docs/09-db-design.md 4.2절).
 */
public enum StatusCode {
	REST,
	WAKE,
	INTEREST,
	ANXIOUS,
	STRONG_WARNING,
	OVER_BUDGET
}
