package com.petgyebu.telo.user.domain;

/**
 * 소셜 로그인 공급자. 계정은 (provider, providerUserId) 조합으로 식별한다(F-QJXRMD).
 *
 * <p>DB에서는 {@code users.provider VARCHAR(10)} + CHECK 제약으로 표현한다. 값을 추가하려면
 * CHECK를 바꾸는 마이그레이션이 함께 필요하다.
 */
public enum AuthProvider {
	KAKAO,
	APPLE
}
