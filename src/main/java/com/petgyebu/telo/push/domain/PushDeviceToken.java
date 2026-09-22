package com.petgyebu.telo.push.domain;

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
 * 한 기기의 FCM 등록 토큰(F-VZFPVW).
 *
 * <p>한 사용자가 기기를 여러 대 쓸 수 있어 사용자당 여러 행이 정상이다. 반면
 * {@code fcm_token}에는 UNIQUE가 걸려 있어 <b>같은 토큰이 두 사용자에게 붙을 수 없다.</b>
 * 기기를 넘겨받은 경우를 충돌로 감지하기 위한 것이며, 등록 시 upsert로 소유자를 갱신한다.
 *
 * <p>토큰 등록·해제 API와 upsert는 T-034, 실제 FCM 발송은 T-035 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "push_device_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushDeviceToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** FCM 등록 토큰. 길어서 VARCHAR(512)다. */
	@Column(nullable = false, length = 512)
	private String fcmToken;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private DevicePlatform platform;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다({@code User.joinedAt}과 같은 이유). */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 기기 토큰을 등록한다.
	 *
	 * @param now 생성·수정 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public PushDeviceToken(User user, String fcmToken, DevicePlatform platform, OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.fcmToken = Objects.requireNonNull(fcmToken, "fcmToken");
		this.platform = Objects.requireNonNull(platform, "platform");
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
