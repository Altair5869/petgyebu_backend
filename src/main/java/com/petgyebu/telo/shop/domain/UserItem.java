package com.petgyebu.telo.shop.domain;

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
 * 사용자가 보유한 아이템 한 건(F-HPWCNJ).
 *
 * <p><b>{@code @ManyToMany}를 쓰지 않았다.</b> {@code users}와 {@code shop_items}를 잇는 연결
 * 테이블이지만 고유 데이터가 5개 붙어 있다({@code itemType}·{@code acquisitionType}·
 * {@code pricePaid}·{@code acquiredAt}·{@code isPlaced}). {@code @ManyToMany}가 만드는 연결
 * 테이블은 FK 두 개가 전부라 이것들을 담을 수 없다. 독립 엔티티로 두고 {@code @ManyToOne}을
 * 둘 건다. <b>반대편 {@code @OneToMany}는 걸지 않는다</b> — 이 프로젝트의 연관은 전부 단방향이다.
 *
 * <p>{@code itemType}은 {@link ShopItem}에서 복사한 <b>비정규화 컬럼</b>이다. 슬롯당 1개 규칙을
 * DB 제약으로 막으려면 이 테이블 자체에 슬롯이 있어야 한다. PostgreSQL의 부분 유니크 인덱스는
 * 해당 테이블의 컬럼만 참조할 수 있어 {@code shop_items}를 조인해 만들 수 없기 때문이다
 * (docs/09-db-design.md 5.4절). 행을 만들 때 한 번 복사하고 이후 바꾸지 않으므로
 * {@code updatable = false}로 막았다.
 *
 * <p>DB 제약 둘이 규칙을 보장한다.
 * <ul>
 *   <li>{@code UNIQUE (user_id, shop_item_id)} — 같은 아이템 중복 구매 금지</li>
 *   <li>{@code UNIQUE (user_id, item_type) WHERE is_placed = TRUE} — 슬롯당 1개.
 *       <b>부분 조건이 있어 배치하지 않은 같은 슬롯 아이템은 여러 개 보유할 수 있다.</b></li>
 * </ul>
 *
 * <p>{@code shop_item_id}의 FK에는 {@code ON DELETE} 절이 없다(기본값 NO ACTION). 상점 아이템은
 * {@code isActive = false}로 판매만 중단하고 삭제하지 않는다.
 *
 * <p>구매·배치·해제 API는 T-044 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "user_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** 상점 아이템은 지워지지 않으므로 이 참조는 끊기지 않는다(FK는 NO ACTION이다). */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shop_item_id", nullable = false)
	private ShopItem shopItem;

	/**
	 * {@link ShopItem#getItemType()}의 복사본. 부분 유니크 인덱스가 참조해야 해서 여기에 둔다.
	 * 한 번 정해지면 바뀌지 않으므로 {@code updatable = false}다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private ItemType itemType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private AcquisitionType acquisitionType;

	/** 구매 시점 가격 스냅샷. 지급({@link AcquisitionType#GRANT})이면 null이다. */
	@Column(updatable = false)
	private Long pricePaid;

	/** DB에도 {@code DEFAULT now()}가 있지만 값은 애플리케이션이 채운다. */
	@Column(nullable = false, updatable = false)
	private OffsetDateTime acquiredAt;

	/** 방에 배치돼 있는지. 배치된 것만 부분 유니크 인덱스의 대상이 된다. */
	@Column(name = "is_placed", nullable = false)
	private boolean isPlaced;

	/**
	 * 아이템을 보유 목록에 넣는다. 획득 직후에는 배치되지 않은 상태(보관함)다.
	 *
	 * <p>{@code itemType}은 인자로 받지 않고 {@code shopItem}에서 복사한다. 두 값이 어긋나면
	 * 슬롯당 1개 규칙이 엉뚱한 슬롯에 걸리기 때문에 호출자가 따로 넘길 여지를 두지 않는다.
	 *
	 * @param pricePaid 구매 시점 가격. 지급이면 null
	 * @param now 획득 시각. 호출자가 KST 기준 시계로 넘긴다
	 */
	public UserItem(
			User user,
			ShopItem shopItem,
			AcquisitionType acquisitionType,
			Long pricePaid,
			OffsetDateTime now) {
		this.user = Objects.requireNonNull(user, "user");
		this.shopItem = Objects.requireNonNull(shopItem, "shopItem");
		this.itemType = shopItem.getItemType();
		this.acquisitionType = Objects.requireNonNull(acquisitionType, "acquisitionType");
		this.pricePaid = pricePaid;
		this.acquiredAt = Objects.requireNonNull(now, "now");
		this.isPlaced = false;
	}
}
