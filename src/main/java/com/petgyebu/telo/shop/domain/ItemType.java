package com.petgyebu.telo.shop.domain;

/**
 * 방 꾸미기 슬롯(F-HPWCNJ, Q16).
 *
 * <p>아이템은 캐릭터에 입히지 않고 반려동물의 방을 꾸민다. 슬롯은 4개이며 각 1개씩 배치한다.
 * <b>선언 순서가 곧 렌더링 z-order다</b> — 벽지 → 바닥 → 집 → (캐릭터) → 장난감. 캐릭터는 집
 * 앞에 앉고 장난감이 가장 앞에 온다. 순서가 슬롯으로 정해져 별도 순서 컬럼이 없다
 * (docs/09-db-design.md 5.2 아래 표).
 *
 * <p>DB의 {@code ck_shop_items_item_type}·{@code ck_user_items_item_type}과 값이 같아야 한다.
 */
public enum ItemType {

	/** 방 배경. */
	WALLPAPER,

	/** 카펫·장판. */
	FLOOR,

	/** 반려동물 집·쿠션. */
	HOUSE,

	/** 소품. 캐릭터보다 앞에 그린다. */
	TOY
}
