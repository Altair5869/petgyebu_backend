package com.petgyebu.telo.category.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 가맹점명 키워드로 카테고리를 정하는 룰 한 건(F-OAVYWT).
 *
 * <p>{@code keywords}는 JSONB 배열이며 GIN 인덱스가 걸려 있다. 매칭 로직은 T-019 범위라
 * 여기에 아직 없다.
 *
 * <p>카테고리 FK에 {@code ON DELETE CASCADE}가 <b>없다.</b> 카테고리는 사용자 소유 데이터가
 * 아니라 고정 시드라, 참조 중인 카테고리 삭제는 거부되는 것이 옳다.
 *
 * <p>룰 자체는 시드 데이터로 관리하며 관리자 화면을 만들지 않는다(Q12). 현재 시드 행은 없다.
 * 실제 키워드 목록이 확정되지 않아 T-019에서 정한다.
 */
@Entity
@Table(name = "merchant_keyword_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantKeywordRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	/** 가맹점명 키워드 배열. DB는 JSONB다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<String> keywords;

	/** 여러 룰이 걸릴 때 높은 값이 이긴다. */
	@Column(nullable = false)
	private short priority;

	public MerchantKeywordRule(Category category, List<String> keywords, short priority) {
		this.category = Objects.requireNonNull(category, "category");
		this.keywords = Objects.requireNonNull(keywords, "keywords");
		this.priority = priority;
	}
}
