package com.petgyebu.telo.push.domain;

import com.petgyebu.telo.budget.domain.BudgetPeriod;
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
 * 예산 사용률 경고 푸시 발송 기록 한 건(F-VZFPVW).
 *
 * <p>DB에 {@code UNIQUE (budget_period_id, threshold_type)}가 있어 <b>같은 기간에 같은
 * 임계값을 두 번 보낼 수 없다.</b> Upstash 키가 유실되거나 배치가 중복 실행돼도 두 번째 발송은
 * 제약 위반으로 막힌다. Redis는 빠른 판정용이고 DB가 최종 방어선이다(Q13).
 *
 * <p>키에 {@code user_id}가 없다 — {@code budget_period_id}가 이미 사용자를 함의한다.
 * {@code reward_grants}의 3열 유니크와 다르다는 점에 주의한다.
 *
 * <p>{@code readAt}은 발송 시점에 null이고 앱에서 알림을 탭할 때 채워진다. KPI "예산 초과 경고
 * 확인율"의 분자다(Q11). 열람 이벤트 수신 API와 실제 FCM 발송은 T-032 이후 범위라 여기에 아직
 * 없다.
 */
@Entity
@Table(name = "push_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** 발송 판정 대상 기간. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "budget_period_id", nullable = false)
	private BudgetPeriod budgetPeriod;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PushThresholdType thresholdType;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime sentAt;

	/** 알림을 탭한 시각. 발송 직후에는 읽지 않은 상태라 null이다. */
	private OffsetDateTime readAt;

	/**
	 * 발송을 기록한다. 기록 시점에는 아직 읽지 않은 상태다.
	 *
	 * @param sentAt 발송 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public PushLog(
			User user, BudgetPeriod budgetPeriod, PushThresholdType thresholdType,
			OffsetDateTime sentAt) {
		this.user = Objects.requireNonNull(user, "user");
		this.budgetPeriod = Objects.requireNonNull(budgetPeriod, "budgetPeriod");
		this.thresholdType = Objects.requireNonNull(thresholdType, "thresholdType");
		this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
		this.readAt = null;
	}
}
