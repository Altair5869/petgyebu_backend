package com.petgyebu.telo.shop.domain;

/**
 * 아이템 획득 경로(F-HPWCNJ).
 *
 * <p>DB의 {@code ck_user_items_acquisition_type}과 값이 같아야 한다.
 */
public enum AcquisitionType {

	/** 크레딧으로 구매. 구매 시점 가격이 {@code price_paid}에 남는다. */
	PURCHASE,

	/** 이벤트·보상 등으로 지급. 지불 금액이 없어 {@code price_paid}가 null이다. */
	GRANT
}
