package com.petgyebu.telo.category.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고정 소비 카테고리 하나(F-OAVYWT).
 *
 * <p><b>{@code id}에 {@code @GeneratedValue}가 없다.</b> 다른 테이블과 달리 identity가 아니라
 * 값을 직접 박는다. 10건이 마이그레이션 시드로 고정돼 있어 ID가 환경마다 달라지면 안 된다
 * (docs/09-db-design.md 3.2절). 미분류는 99이며, {@code transactions.category_id}가
 * {@code DEFAULT 99}로 이 값을 참조한다(T-014).
 *
 * <p>사용자가 추가·삭제할 수 없다(Q7). 카테고리를 늘리려면 마이그레이션을 새로 추가한다.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

	/** 시드로 고정된 값이다. 자동 생성하지 않는다. */
	@Id
	private Short id;

	@Column(nullable = false, length = 30)
	private String code;

	@Column(nullable = false, length = 30)
	private String name;

	@Column(nullable = false)
	private short sortOrder;

	public Category(Short id, String code, String name, short sortOrder) {
		this.id = Objects.requireNonNull(id, "id");
		this.code = Objects.requireNonNull(code, "code");
		this.name = Objects.requireNonNull(name, "name");
		this.sortOrder = sortOrder;
	}
}
