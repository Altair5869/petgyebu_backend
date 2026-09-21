package com.petgyebu.telo.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상점에 진열되는 방 꾸미기 아이템 하나(F-HPWCNJ).
 *
 * <p><b>판매 중단은 {@code isActive}를 false로 내리는 것이며 행을 지우지 않는다.</b> 이미 산
 * 사람의 보유({@link UserItem})는 그대로 유지돼야 한다. {@code user_items.shop_item_id}의 FK에
 * {@code ON DELETE CASCADE}가 없는 것도 같은 이유다.
 *
 * <p>가격은 DB의 {@code CHECK (price_credits BETWEEN 100 AND 500)}이 명세가 정한 가격대를
 * 벗어나지 못하게 막는다(docs/02-requirements-features.md:390).
 *
 * <p><b>시드 행이 없다.</b> 실제 아이템의 {@code code}·{@code name}·{@code imageUrl}·
 * {@code priceCredits}가 어느 문서에도 없고 {@code imageUrl}이 NOT NULL이라 이미지 에셋 없이는
 * 채울 수 없다. 실제 시드는 에셋과 함께 T-044에서 정한다.
 *
 * <p>상점 목록·구매 API는 T-044 범위라 여기에 아직 없다.
 */
@Entity
@Table(name = "shop_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShopItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 시드 데이터 식별용. DB에 UNIQUE가 걸려 있다. */
	@Column(nullable = false, length = 50)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ItemType itemType;

	/** Cloud Storage 경로. */
	@Column(nullable = false, length = 500)
	private String imageUrl;

	@Column(nullable = false)
	private long priceCredits;

	/** 상점 설명 문구. 없으면 null이다. */
	@Column(length = 255)
	private String description;

	@Column(nullable = false)
	private short sortOrder;

	/** false면 판매 중단이다. 이미 산 사람의 보유는 유지된다. */
	@Column(name = "is_active", nullable = false)
	private boolean isActive;

	/**
	 * 상점 아이템을 만든다. 만든 직후에는 판매 중이다.
	 *
	 * @param description 상점 설명 문구. 없으면 null
	 */
	public ShopItem(
			String code,
			String name,
			ItemType itemType,
			String imageUrl,
			long priceCredits,
			String description,
			short sortOrder) {
		this.code = Objects.requireNonNull(code, "code");
		this.name = Objects.requireNonNull(name, "name");
		this.itemType = Objects.requireNonNull(itemType, "itemType");
		this.imageUrl = Objects.requireNonNull(imageUrl, "imageUrl");
		this.priceCredits = priceCredits;
		this.description = description;
		this.sortOrder = sortOrder;
		this.isActive = true;
	}
}
