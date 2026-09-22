package com.petgyebu.telo.transaction.domain;

import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.account.domain.Account;
import com.petgyebu.telo.category.domain.Category;
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
 * 계좌에서 수집한 거래 한 건(F-OAVYWT).
 *
 * <p><b>{@code user}와 {@code account}를 둘 다 들고 있다.</b> 비정규화이며 실수가 아니다. 예산
 * 사용률·소비 요약·카테고리별 분석이 전부 "사용자 + 기간" 기준이라, {@code accounts}를 거쳐
 * 조인하면 모든 집계 쿼리에 조인이 하나씩 붙는다(docs/09-db-design.md 3.4절).
 *
 * <p>그 대가로 {@code account}의 주인과 {@code user}가 어긋날 수 있다. DB는 이것을 막지 않는다.
 * 막으려면 {@code accounts}에 {@code UNIQUE (id, user_id)}를 새로 걸고 복합 FK를 걸어야 하는데
 * 설계 문서에 없는 제약이라 넣지 않았다. 문서가 정한 방어선은 "거래 저장은 반드시 계좌 조회를
 * 거친 경로로만 수행한다"이며, 그 경로는 T-015에서 만든다.
 *
 * <p>{@code amount}는 <b>언제나 양수다</b>(DB에 {@code CHECK (amount > 0)}가 있다). 수입·지출
 * 방향은 {@link TxnType}이 정한다.
 *
 * <p>{@code initialClassificationSource}는 최초 값에서 바뀌지 않는다. 사용자가 카테고리를 고쳐도
 * 갱신하지 않으므로 세터를 두지 않았고 {@code updatable = false}다.
 *
 * <p>{@code linkedRefundTransaction}은 같은 테이블을 가리키는 자기참조다. 원거래와 환불을 각각
 * 행으로 저장하고 이 열로 잇는다. FK에 {@code ON DELETE} 절이 없어 기본값 {@code NO ACTION}이다.
 *
 * <p>중복 판정(T-016)·이체 연결(T-017·T-018)·자동 분류(T-019)·거래 수정은 이 Task 범위가 아니라
 * 상태를 바꾸는 메서드가 아직 없다.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 비정규화된 소유자. {@code account.getUser()}와 같아야 하지만 DB가 강제하지는 않는다. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	/** 대행사(코드에프)가 준 거래 식별자. 계좌 단위로 유일하다. */
	@Column(nullable = false, length = 255)
	private String codefTransactionId;

	@Column(nullable = false)
	private OffsetDateTime transactedAt;

	/** 언제나 양수다. 방향은 {@link #txnType}이 정한다. */
	@Column(nullable = false)
	private long amount;

	/** 거래처. 코드에프가 주지 않는 거래가 있어 null을 허용한다. */
	@Column(length = 255)
	private String merchant;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private TxnType txnType;

	/** 매칭되는 룰이 없으면 미분류(id 99)다. DB에도 {@code DEFAULT 99}가 있다. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	/** 최초 분류 경로. 사용자가 카테고리를 바꿔도 갱신하지 않는다. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private ClassificationSource initialClassificationSource;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TransferStatus transferStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RefundStatus refundStatus;

	/** 자기참조. 원거래↔환불을 잇는다. 연결이 없으면 null이다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "linked_refund_transaction_id")
	private Transaction linkedRefundTransaction;

	/** 사용자 메모. 기본은 null이다. */
	@Column(length = 255)
	private String memo;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다({@code Account}와 같은 이유). */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 수집한 거래를 만든다. 수집 직후에는 이체도 환불도 아니고 메모도 연결도 없다.
	 *
	 * @param user {@code account}의 주인과 같아야 한다. 호출자가 계좌 조회를 거쳐 넘긴다
	 * @param amount 양수여야 한다. DB에 {@code CHECK (amount > 0)}가 있다
	 * @param merchant 코드에프가 주지 않으면 null
	 * @param category 매칭되는 룰이 없으면 미분류(id 99)
	 * @param now 생성·수정 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public Transaction(
			User user,
			Account account,
			String codefTransactionId,
			OffsetDateTime transactedAt,
			long amount,
			String merchant,
			TxnType txnType,
			Category category,
			ClassificationSource initialClassificationSource,
			OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.account = Objects.requireNonNull(account, "account");
		this.codefTransactionId = Objects.requireNonNull(codefTransactionId, "codefTransactionId");
		this.transactedAt = Objects.requireNonNull(transactedAt, "transactedAt");
		this.amount = amount;
		this.merchant = merchant;
		this.txnType = Objects.requireNonNull(txnType, "txnType");
		this.category = Objects.requireNonNull(category, "category");
		this.initialClassificationSource =
				Objects.requireNonNull(initialClassificationSource, "initialClassificationSource");
		this.transferStatus = TransferStatus.NONE;
		this.refundStatus = RefundStatus.NONE;
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
