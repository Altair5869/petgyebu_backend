package com.petgyebu.telo.transaction.domain;

/**
 * 거래가 처음 분류된 경로(docs/09-db-design.md 3.4절).
 *
 * <p>{@link #AUTO_MATCHED}는 가맹점 키워드 룰이 맞은 것, {@link #UNCLASSIFIED}는 맞는 룰이 없어
 * 미분류(카테고리 99)로 들어온 것이다.
 *
 * <p><b>최초 값에서 바뀌지 않는다.</b> 사용자가 나중에 카테고리를 고쳐도 갱신하지 않는다.
 * 자동 분류 정확도 KPI의 모수라 갱신하면 지표가 무너진다.
 */
public enum ClassificationSource {
	AUTO_MATCHED,
	UNCLASSIFIED
}
