package com.petgyebu.telo.reward.domain;

import com.petgyebu.telo.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 크레딧 잔액(F-EZZFNU·F-HPWCNJ).
 *
 * <p><b>이 테이블만 독립 PK가 없다.</b> 사용자당 한 행이라 {@code user_id}가 곧 PK이며 동시에
 * {@code users}를 가리키는 FK다(docs/09-db-design.md 5.1절). 다른 테이블에 있는
 * {@code GENERATED ALWAYS AS IDENTITY}가 여기에는 없다.
 *
 * <p><b>매핑은 {@code @MapsId}를 골랐다.</b> 대안인 {@code @Id @OneToOne User user}는 식별자
 * 타입이 {@link User}가 되어 {@code JpaRepository<CreditBalance, User>}가 되고,
 * {@code findById(userId)}에 사용자 ID를 바로 넘길 수 없다. T-043·T-044가 사용자 ID로 잔액을
 * 조회·잠그는 경로라 식별자는 {@link Long}이어야 한다. {@code @MapsId}는 {@code userId} 필드를
 * 식별자로 두고 연관에서 값을 끌어오므로 컬럼은 하나({@code user_id})뿐이고 식별자는 Long이다.
 *
 * <p>DB에 {@code CHECK (balance >= 0)}가 있어 잔액이 음수가 되는 갱신은 거부된다. 애플리케이션
 * 검사와 {@code SELECT ... FOR UPDATE}에 더한 마지막 방어선이며, <b>그 잠금 로직은 T-043·T-044
 * 범위라 여기에 아직 없다.</b>
 */
@Entity
@Table(name = "credit_balances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditBalance {

	/** PK 겸 FK다. 값은 {@link #user}에서 {@code @MapsId}가 끌어온다. */
	@Id
	private Long userId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false)
	private long balance;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 잔액 행을 연다. 가입 시 0으로 시작한다.
	 *
	 * @param now 수정 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public CreditBalance(User user, long balance, OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.balance = balance;
		this.updatedAt = Objects.requireNonNull(now, "now");
	}
}
