package com.petgyebu.telo.sync.domain;

/**
 * 동기화 시도를 누가 일으켰는지(docs/09-db-design.md 3.6절).
 *
 * <p>수집 성공률 95% 지표는 {@link #SCHEDULED}만 모수로 센다. 사용자가 직접 누른
 * {@link #MANUAL}은 재시도 성격이라 섞이면 지표가 왜곡된다.
 */
public enum TriggerType {
	SCHEDULED,
	MANUAL
}
