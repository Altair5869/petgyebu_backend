package com.petgyebu.telo.transaction.domain;

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
 * 계좌 간 이체로 판정된 출금↔입금 한 쌍(F-OAVYWT).
 *
 * <p>DB에서 <b>양쪽 거래 식별자에 각각 독립 UNIQUE</b>가 걸려 있다. 복합 UNIQUE가 아니다.
 * 하나의 출금이 여러 입금과 엮이는 상황을 DB가 막는다. 복합으로 묶으면 (출금 A, 입금 X)와
 * (출금 A, 입금 Y)가 둘 다 들어가 제약의 목적이 사라진다(docs/09-db-design.md 3.5절).
 *
 * <p>연결을 해제하면 이 행을 <b>삭제</b>하고 양쪽 거래의 {@code transferStatus}를
 * {@code UNLINKED}로 바꾼다. 해제 이력은 {@code transaction_edit_histories}에 남는다. 그
 * 로직은 T-029 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "transfer_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferLink {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "withdrawal_transaction_id", nullable = false, unique = true)
	private Transaction withdrawalTransaction;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "deposit_transaction_id", nullable = false, unique = true)
	private Transaction depositTransaction;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private LinkStatus linkStatus;

	/** 판단 근거(금액·시각 차이 등). 없으면 null이다. */
	@Column(length = 255)
	private String matchReason;

	/** 사용자 확인 시각. 자동 연결만 된 상태에서는 null이다. */
	private OffsetDateTime confirmedAt;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다. */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	/**
	 * 이체 연결을 만든다.
	 *
	 * @param matchReason 판단 근거. 없으면 null
	 * @param confirmedAt 사용자 확인 시각. 자동 연결 단계에서는 null
	 * @param now 생성 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public TransferLink(
			Transaction withdrawalTransaction,
			Transaction depositTransaction,
			LinkStatus linkStatus,
			String matchReason,
			OffsetDateTime confirmedAt,
			OffsetDateTime now) {
		this.withdrawalTransaction =
				Objects.requireNonNull(withdrawalTransaction, "withdrawalTransaction");
		this.depositTransaction = Objects.requireNonNull(depositTransaction, "depositTransaction");
		this.linkStatus = Objects.requireNonNull(linkStatus, "linkStatus");
		this.matchReason = matchReason;
		this.confirmedAt = confirmedAt;
		this.createdAt = Objects.requireNonNull(now, "now");
	}
}
