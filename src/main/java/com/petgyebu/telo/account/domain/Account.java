package com.petgyebu.telo.account.domain;

import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.user.domain.User;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 연결한 은행 계좌 하나(F-TEDWWF).
 *
 * <p>{@code bankCode}는 숫자처럼 보이지만 {@link String}이다. 은행 조직코드는 {@code "004"}처럼
 * 앞자리가 0일 수 있어 정수로 다루면 {@code 4}가 되어 은행을 잘못 식별한다. DB 컬럼도
 * {@code VARCHAR(10)}이다.
 *
 * <p>계좌번호는 코드에프가 준 <b>마스킹된 값만</b> 저장한다. 원본 계좌번호는 보관하지 않는다
 * (docs/09-db-design.md 3.1절).
 *
 * <p>{@code displayMode}는 {@code includedInBudget = false}일 때만 의미가 있다. 포함 상태에서는
 * 무시하며, 이 조건을 DB 제약으로 표현하지 않는다.
 *
 * <p>DB에 {@code UNIQUE (user_id, bank_code, masked_account_no)}가 있어 같은 계좌를 두 번 연결할
 * 수 없다. 동일 은행의 복수 계좌는 마스킹 번호가 달라 함께 저장된다.
 *
 * <p>동기화·재인증 상태를 바꾸는 경로(코드에프 연동, 2-way 추가인증, 동기화 스케줄러)는 T-009·
 * T-015 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** 은행 조직코드. 앞자리 0이 살아 있어야 해서 문자열이다. */
	@Column(nullable = false, length = 10)
	private String bankCode;

	/** 코드에프 커넥티드아이디. 탈퇴·계좌 해제 시 해지에 필요하다. */
	@Column(nullable = false, length = 255)
	private String codefConnectedId;

	/** 마스킹된 계좌번호. 원본은 저장하지 않는다. */
	@Column(nullable = false, length = 50)
	private String maskedAccountNo;

	/** 코드에프가 주면 저장한다. 안 주는 은행이 있어 null을 허용한다. */
	@Column(length = 100)
	private String accountName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConsentStatus consentStatus;

	private OffsetDateTime consentExpiresAt;

	/** 마지막 성공 동기화 시각. 한 번도 성공하지 않았으면 null이다. */
	private OffsetDateTime lastSyncedAt;

	@Column(nullable = false)
	private boolean reauthRequired;

	@Column(nullable = false)
	private boolean includedInBudget;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private DisplayMode displayMode;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다({@code User.joinedAt}과 같은 이유). */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 계좌를 연결한다. 연결 직후에는 동의가 유효하고, 가계부 집계에 포함되며, 재인증이 필요 없다.
	 *
	 * @param consentExpiresAt 동의 만료 시각. 만료 개념이 없는 은행은 null
	 * @param now 생성·수정 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public Account(
			User user,
			String bankCode,
			String codefConnectedId,
			String maskedAccountNo,
			String accountName,
			OffsetDateTime consentExpiresAt,
			OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.bankCode = Objects.requireNonNull(bankCode, "bankCode");
		this.codefConnectedId = Objects.requireNonNull(codefConnectedId, "codefConnectedId");
		this.maskedAccountNo = Objects.requireNonNull(maskedAccountNo, "maskedAccountNo");
		this.accountName = accountName;
		this.consentStatus = ConsentStatus.ACTIVE;
		this.consentExpiresAt = consentExpiresAt;
		this.reauthRequired = false;
		this.includedInBudget = true;
		this.displayMode = DisplayMode.BADGE;
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	/**
	 * `updated_at`을 갱신한다. DB의 {@code DEFAULT now()}는 INSERT에만 발화하므로
	 * 이 콜백이 없으면 수정 경로가 생기는 순간 값이 생성 시각에 고정된다.
	 *
	 * <p>시각은 {@link AppZone#clock()}에서 얻는다. KST 단일 출처 규칙이다(T-052).
	 * JPA를 지나가는 수정만 덮는다 — 벌크 UPDATE나 raw SQL은 이 콜백을 타지 않는다.
	 */
	@PreUpdate
	void onUpdate() {
		this.updatedAt = OffsetDateTime.now(AppZone.clock());
	}
}
