package com.petgyebu.telo.sync.domain;

import com.petgyebu.telo.account.domain.Account;
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
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계좌 동기화 시도 기록 한 건(F-OAVYWT).
 *
 * <p><b>append-only다.</b> 한 번 쓰면 고치지 않으므로 다른 테이블과 달리
 * {@code updated_at}이 없다(docs/09-db-design.md 3.6절).
 *
 * <p>{@code account}가 <b>null일 수 있다.</b> 계좌 FK가 {@code ON DELETE SET NULL}이라 사용자가
 * 계좌 연결을 해제해도 이 기록은 남는다. 수집 성공률 KPI의 모수가 계좌 해제로 줄어들면 지표가
 * 왜곡되기 때문이다. 계좌가 사라져도 어느 은행이었는지 알 수 있게 {@code bankCode}를 따로 들고
 * 있는다.
 *
 * <p>동기화 스케줄러와 재시도는 T-020 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "sync_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** 계좌 연결이 해제되면 null이 된다({@code ON DELETE SET NULL}). */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "account_id")
	private Account account;

	/** 계좌가 지워져도 은행은 알 수 있게 따로 저장한다. 앞자리 0 때문에 문자열이다. */
	@Column(nullable = false, length = 10)
	private String bankCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TriggerType triggerType;

	@Column(nullable = false)
	private OffsetDateTime attemptedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private SyncResult result;

	/** 실패 사유. 성공이면 null이다. */
	@Column(length = 500)
	private String failureReason;

	@Column(nullable = false)
	private short retryCount;

	/**
	 * 동기화 시도 기록을 남긴다.
	 *
	 * @param bankCode 계좌가 나중에 지워져도 남아야 하므로 계좌에서 복사해 둔다
	 * @param failureReason 실패 사유. 성공이면 null
	 * @param retryCount 이 시도가 몇 번째 재시도인지. 첫 시도는 0
	 */
	public SyncAttempt(
			User user,
			Account account,
			String bankCode,
			TriggerType triggerType,
			OffsetDateTime attemptedAt,
			SyncResult result,
			String failureReason,
			short retryCount) {
		this.user = Objects.requireNonNull(user, "user");
		this.account = account;
		this.bankCode = Objects.requireNonNull(bankCode, "bankCode");
		this.triggerType = Objects.requireNonNull(triggerType, "triggerType");
		this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
		this.result = Objects.requireNonNull(result, "result");
		this.failureReason = failureReason;
		this.retryCount = retryCount;
	}
}
