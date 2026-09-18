package com.petgyebu.telo.user.domain;

/**
 * 동의 항목 종류.
 *
 * <p>{@link #TERMS_OF_SERVICE}와 {@link #PRIVACY_POLICY}는 가입 시점에,
 * {@link #CODEF_THIRD_PARTY}와 {@link #FINANCIAL_DATA_INQUIRY}는 계좌 연결 직전에 받는다
 * (F-QJXRMD rules, docs/09-db-design.md 2.2절).
 */
public enum ConsentType {
	TERMS_OF_SERVICE,
	PRIVACY_POLICY,
	CODEF_THIRD_PARTY,
	FINANCIAL_DATA_INQUIRY
}
