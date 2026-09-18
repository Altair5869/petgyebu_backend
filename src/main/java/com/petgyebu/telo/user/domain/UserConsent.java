package com.petgyebu.telo.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 동의 기록. append-only다.
 *
 * <p>동의를 갱신할 때 기존 행을 수정하지 않고 새 행을 추가한다. 언제 어느 버전에 동의했는지가
 * 기록으로 남아야 하기 때문이다(docs/09-db-design.md 2.2절). 그래서 감사 컬럼도 두지 않는다.
 *
 * <p>FK는 DB에서 {@code ON DELETE CASCADE}다. 탈퇴(F-ZPNVKT)는 {@code users} 한 행을 지우는
 * 것으로 끝나며 삭제 순서를 코드에서 관리하지 않는다.
 */
@Entity
@Table(name = "user_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ConsentType consentType;

	@Column(nullable = false, length = 20)
	private String consentVersion;

	@Column(nullable = false)
	private OffsetDateTime consentedAt;

	public UserConsent(User user, ConsentType consentType, String consentVersion, OffsetDateTime consentedAt) {
		this.user = Objects.requireNonNull(user, "user");
		this.consentType = Objects.requireNonNull(consentType, "consentType");
		this.consentVersion = Objects.requireNonNull(consentVersion, "consentVersion");
		this.consentedAt = Objects.requireNonNull(consentedAt, "consentedAt");
	}
}
