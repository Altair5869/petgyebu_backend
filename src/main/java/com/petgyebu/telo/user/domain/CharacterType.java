package com.petgyebu.telo.user.domain;

/**
 * 사용자가 고른 반려동물 캐릭터. 최초 설정 전에는 값이 없다(null).
 *
 * <p>사용자당 정확히 하나라서 별도 테이블 없이 {@code users}의 컬럼으로 둔다
 * (docs/09-db-design.md 2.1절).
 */
public enum CharacterType {
	DOG,
	CAT
}
