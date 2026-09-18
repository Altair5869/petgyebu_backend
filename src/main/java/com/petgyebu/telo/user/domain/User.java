package com.petgyebu.telo.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 계정. 소셜 로그인 전용이라 비밀번호 컬럼이 없다(F-QJXRMD rules).
 *
 * <p>계정은 {@code (provider, providerUserId)} 조합으로 고유하게 식별한다. 이메일은 애플의
 * 이메일 가리기와 카카오의 선택 동의 때문에 null이거나 중복일 수 있어 식별자로 쓰지 않는다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private AuthProvider provider;

	@Column(nullable = false, length = 255)
	private String providerUserId;

	@Column(length = 320)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private CharacterType characterType;

	private OffsetDateTime characterChangedAt;

	/**
	 * KPI 코호트 기준 t=0. DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다.
	 * {@code insertable = false}로 두면 저장 직후 객체 필드가 null이라 응답에 쓸 수 없다.
	 *
	 * <p>{@code updatable = false}인 이유: 가입 후 24시간·7일 코호트의 기준점이라 한 번 정해지면
	 * 바뀌면 안 된다. 실수로 갱신되는 경로를 매핑 단계에서 막는다.
	 */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime joinedAt;

	private OffsetDateTime lastLoginAt;

	public User(AuthProvider provider, String providerUserId, String email, OffsetDateTime joinedAt) {
		this.provider = Objects.requireNonNull(provider, "provider");
		this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
		this.email = email;
		this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
	}
}
