package com.petgyebu.telo.category;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.petgyebu.telo.category.domain.Category;
import com.petgyebu.telo.category.domain.MerchantKeywordRule;
import com.petgyebu.telo.category.repository.CategoryRepository;
import com.petgyebu.telo.category.repository.MerchantKeywordRuleRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code categories}·{@code merchant_keyword_rules} 스키마가 실제 PostgreSQL에서 설계대로
 * 동작하는지 본다({@code AccountSchemaTest}와 같은 방식).
 *
 * <p>이 테이블들은 앞선 세 테이블과 세 가지가 다르다. 셋 다 여기서 못 박는다.
 * <ol>
 *   <li>{@code categories.id}가 identity가 아니다 — 시드로 고정된 값을 직접 박는다</li>
 *   <li>마이그레이션에 {@code INSERT}가 있다 — 시드 10건의 값까지 단언한다</li>
 *   <li>{@code keywords}가 JSONB이고 인덱스가 B-tree가 아니라 GIN이다</li>
 * </ol>
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("categories·merchant_keyword_rules 스키마 제약 검증")
class CategorySchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	/** 시드와 부딪히지 않게 테스트가 직접 만드는 카테고리는 이 대역을 쓴다. */
	private static final short TEST_CATEGORY_ID_BASE = 1000;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private MerchantKeywordRuleRepository merchantKeywordRuleRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("카테고리 10건이 명세 그대로 시드된다 — id·code·name을 각각 단언")
	void seededCategoriesMatchSpecification() {
		// 개수만 세면 값이 틀려도 통과한다. 세 열을 전부 본다.
		// 미분류가 99인 것은 transactions.category_id DEFAULT 99가 의존하는 값이다(T-014).
		// id는 SMALLINT라 JDBC 드라이버가 Integer로 준다. 단언 값 타입을 드라이버 사정에
		// 맡기지 않으려고 SQL에서 명시적으로 integer로 캐스팅한다.
		List<Map<String, Object>> rows = new JdbcTemplate(dataSource).queryForList(
				"SELECT CAST(id AS integer) AS id, code, name FROM categories "
						+ "WHERE id < ? ORDER BY id",
				TEST_CATEGORY_ID_BASE);

		assertThat(rows)
				.as("시드된 카테고리가 10건이 아니다")
				.hasSize(10)
				.extracting("id", "code", "name")
				.containsExactly(
						tuple(1, "FOOD", "식비"),
						tuple(2, "CAFE_SNACK", "카페·간식"),
						tuple(3, "TRANSPORT", "교통"),
						tuple(4, "SHOPPING", "쇼핑"),
						tuple(5, "MEDICAL", "의료·건강"),
						tuple(6, "CULTURE", "문화·여가"),
						tuple(7, "HOUSING_COMM", "주거·통신"),
						tuple(8, "FINANCE_INSURANCE", "금융·보험"),
						tuple(9, "SOCIAL_EVENT", "경조사비"),
						tuple(99, "UNCLASSIFIED", "미분류"));
	}

	@Test
	@DisplayName("sort_order는 id와 같은 값이다 — 미분류가 맨 뒤로 간다")
	void sortOrderFollowsId() {
		List<Map<String, Object>> rows = new JdbcTemplate(dataSource).queryForList(
				"SELECT id, sort_order FROM categories WHERE id < ? AND sort_order <> id",
				TEST_CATEGORY_ID_BASE);

		assertThat(rows)
				.as("sort_order가 id와 다른 시드 행이 있다")
				.isEmpty();
	}

	@Test
	@DisplayName("categories.id는 identity가 아니다 — 값을 주지 않으면 INSERT가 실패한다")
	void categoryIdIsNotGenerated() {
		// identity로 바뀌면 ID가 환경마다 달라진다. transactions.category_id DEFAULT 99도
		// 근거를 잃는다. 그런데 identity로 바꿔도 다른 단언은 전부 통과하므로 여기서 잡는다.
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO categories (code, name, sort_order) VALUES (?, ?, ?)",
				"NO_ID_GIVEN", "아이디없음", 1000))
				.as("id를 주지 않은 INSERT가 성공했다. id가 identity로 바뀌었다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("id");

		// 컬럼 정의 자체도 못 박는다. identity 컬럼이면 is_identity가 'YES'가 돌아온다.
		// 타입도 함께 본다. SMALLINT를 INTEGER로 바꿔도 Hibernate validate는 Short↔integer를
		// 문제 삼지 않고, PostgreSQL은 FK를 smallint = integer로 허용하며, 시드 단언은
		// CAST(id AS integer)로 읽어 타입을 보지 못한다. 이 단언이 없으면 아무도 못 잡는다.
		Map<String, Object> idColumn = jdbc.queryForMap(
				"SELECT is_identity, udt_name FROM information_schema.columns "
						+ "WHERE table_name = 'categories' AND column_name = 'id'");
		assertThat(idColumn.get("is_identity"))
				.as("categories.id가 identity 컬럼이다. 시드 ID가 환경마다 달라진다")
				.isEqualTo("NO");
		assertThat(idColumn.get("udt_name"))
				.as("categories.id가 SMALLINT가 아니다(docs/09-db-design.md 3.2절)")
				.isEqualTo("int2");
	}

	@Test
	@DisplayName("같은 code를 가진 카테고리를 둘 만들 수 없다")
	void duplicateCategoryCodeIsRejected() {
		categoryRepository.saveAndFlush(category((short) (TEST_CATEGORY_ID_BASE + 1), "DUP_CODE"));

		assertThatThrownBy(() -> categoryRepository.saveAndFlush(
				category((short) (TEST_CATEGORY_ID_BASE + 2), "DUP_CODE")))
				.as("UNIQUE (code)가 걸려 있지 않다")
				.isInstanceOf(DataIntegrityViolationException.class)
				// 어떤 제약이 걸었는지까지 본다. 이름을 확인하지 않으면 PK 위반으로 실패해도
				// 이 테스트가 초록이 된다.
				.rootCause()
				.hasMessageContaining("uq_categories_code");
	}

	@Test
	@DisplayName("code가 다르면 카테고리를 얼마든지 추가할 수 있다")
	void differentCategoryCodeIsAllowed() {
		categoryRepository.saveAndFlush(category((short) (TEST_CATEGORY_ID_BASE + 11), "OTHER_A"));

		// 유니크가 code가 아닌 다른 열까지 묶으면(예: name) 여기서 깨진다.
		Category second = categoryRepository.saveAndFlush(
				category((short) (TEST_CATEGORY_ID_BASE + 12), "OTHER_B"));

		assertThat(categoryRepository.findById(second.getId()))
				.as("code가 다른 카테고리가 저장되지 않았다")
				.isPresent();
	}

	@Test
	@DisplayName("keywords에 GIN 인덱스가 있다 — 이름·대상 열·인덱스 종류까지")
	void keywordsHasGinIndex() {
		// 종류를 보지 않으면 B-tree로 바뀌어도 통과한다. 이름과 열은 그대로이기 때문이다.
		// B-tree로는 JSONB 포함 연산(@>)을 태울 수 없어 T-019 자동 분류가 전수 스캔이 된다.
		assertIndex("merchant_keyword_rules", "ix_merchant_keyword_rules_keywords",
				"gin", "keywords", null);
	}

	@Test
	@DisplayName("keywords는 JSONB이며 배열이 그대로 오간다")
	void keywordsRoundTripsAsJsonb() {
		Category category = categoryRepository.saveAndFlush(
				category((short) (TEST_CATEGORY_ID_BASE + 21), "JSONB_ROUNDTRIP"));

		MerchantKeywordRule saved = merchantKeywordRuleRepository.saveAndFlush(
				new MerchantKeywordRule(category, List.of("스타벅스", "투썸"), (short) 10));

		assertThat(merchantKeywordRuleRepository.findById(saved.getId()))
				.get()
				.satisfies(found -> {
					assertThat(found.getKeywords()).containsExactly("스타벅스", "투썸");
					assertThat(found.getPriority()).isEqualTo((short) 10);
				});

		// 컬럼 타입을 못 박는다. varchar로 바뀌면 GIN 인덱스도 포함 연산도 성립하지 않는다.
		String udtName = new JdbcTemplate(dataSource).queryForObject(
				"SELECT udt_name FROM information_schema.columns "
						+ "WHERE table_name = 'merchant_keyword_rules' AND column_name = 'keywords'",
				String.class);
		assertThat(udtName)
				.as("keywords 컬럼이 JSONB가 아니다")
				.isEqualTo("jsonb");
	}

	@Test
	@DisplayName("룰이 참조 중인 카테고리는 지울 수 없다 — FK는 CASCADE가 아니라 NO ACTION이다")
	void deletingReferencedCategoryIsRejected() {
		short categoryId = (short) (TEST_CATEGORY_ID_BASE + 31);
		Category category = categoryRepository.saveAndFlush(category(categoryId, "FK_NO_ACTION"));
		merchantKeywordRuleRepository.saveAndFlush(
				new MerchantKeywordRule(category, List.of("키워드"), (short) 0));

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		// ON DELETE CASCADE가 붙으면 이 DELETE가 조용히 성공하면서 룰까지 사라진다.
		// 카테고리는 사용자 소유 데이터가 아니라 고정 시드라 삭제가 거부되는 것이 옳다.
		assertThatThrownBy(() -> jdbc.update("DELETE FROM categories WHERE id = ?", categoryId))
				.as("참조 중인 카테고리가 삭제됐다. FK에 ON DELETE CASCADE가 붙어 있다")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(categoryRepository.findById(categoryId))
				.as("카테고리가 삭제됐다")
				.isPresent();
	}

	@Test
	@DisplayName("키워드 룰 시드는 넣지 않았다 — 실제 룰은 T-019에서 정한다")
	void noKeywordRulesAreSeeded() {
		// 문서 어디에도 확정된 키워드 목록이 없다. 지어낸 키워드가 들어오면 여기서 깨진다.
		Integer seeded = new JdbcTemplate(dataSource).queryForObject(
				"SELECT count(*) FROM merchant_keyword_rules WHERE category_id < ?",
				Integer.class, TEST_CATEGORY_ID_BASE);

		assertThat(seeded)
				.as("시드 카테고리를 참조하는 키워드 룰이 들어와 있다")
				.isZero();
	}

	@Test
	@DisplayName("컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지")
	void columnTypesMatchDesign() {
		// 이 테이블에는 CHECK가 없어 허용 케이스 축은 해당 사항이 없다. 대신 시드 값이
		// 전부 고정이라 길이가 줄면 시드 INSERT부터 깨지는데, 길이를 늘리는 변이는 조용하다.
		assertColumnType("categories", "code", "varchar", 30);
		assertColumnType("categories", "name", "varchar", 30);
		assertColumnType("categories", "id", "int2", null);
		assertColumnType("categories", "sort_order", "int2", null);

		assertColumnType("merchant_keyword_rules", "keywords", "jsonb", null);
		assertColumnType("merchant_keyword_rules", "priority", "int2", null);
		assertColumnType("merchant_keyword_rules", "category_id", "int2", null);
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다 — 빠진 것도 더 붙은 것도 없다")
	void nullabilityMatchesDesign() {
		assertNullability("categories", Map.ofEntries(
				entry("id", "NO"),
				entry("code", "NO"),
				entry("name", "NO"),
				entry("sort_order", "NO")));

		assertNullability("merchant_keyword_rules", Map.ofEntries(
				entry("id", "NO"),
				entry("category_id", "NO"),
				entry("keywords", "NO"),
				entry("priority", "NO")));
	}

	/**
	 * 테이블의 NOT NULL 구성이 설계와 정확히 일치하는지 본다.
	 *
	 * <p>{@code NOT NULL}은 인덱스와 달리 틀린 답을 낸다. 그런데 마이그레이션에서 지워도
	 * 아무것도 깨지지 않는다. Hibernate의 {@code ddl-auto: validate}는 nullability를 보지
	 * 않고, 엔티티의 {@code @Column(nullable = false)}는 DDL 생성용이라 Flyway가 테이블을
	 * 만드는 이 구성에서는 아무 일도 하지 않는다. 제약 테스트도 늘 제대로 된 값을 넣으므로
	 * 빈 값을 막는 규칙이 있든 없든 결과가 같다. 빌드는 초록인데 null이 들어온다.
	 *
	 * <p>컬럼을 하나씩 보지 않고 테이블 전체의 {@code column_name → is_nullable} 맵을
	 * 통째로 비교한다. 그래야 양방향으로 잡힌다. {@code NOT NULL}이 사라지는 것뿐 아니라
	 * 원래 null을 허용하던 컬럼에 {@code NOT NULL}이 붙는 것, 컬럼이 늘거나 없어지는 것도
	 * 함께 걸린다.
	 *
	 * @param expected 컬럼명 → {@code "NO"}(NOT NULL) 또는 {@code "YES"}(null 허용)
	 */
	private void assertNullability(String tableName, Map<String, String> expected) {
		List<Map<String, Object>> columns = new JdbcTemplate(dataSource).queryForList(
				"SELECT column_name, is_nullable FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = ?",
				tableName);

		Map<String, String> actual = columns.stream().collect(Collectors.toMap(
				column -> (String) column.get("column_name"),
				column -> (String) column.get("is_nullable")));

		assertThat(actual)
				.as("%s 테이블의 NOT NULL 구성이 설계와 다르다", tableName)
				.containsExactlyInAnyOrderEntriesOf(expected);
	}

	/**
	 * 인덱스가 설계대로 존재하는지 본다.
	 *
	 * <p>{@code AccountSchemaTest.assertIndex}와 같은 취지지만 <b>인덱스 종류까지</b> 본다.
	 * GIN을 B-tree로 바꿔도 인덱스 이름과 대상 열은 그대로라, 열 목록만 보는 단언은 그 변이를
	 * 통과시킨다. {@code indexdef}의 {@code USING gin (keywords)} 부분을 통째로 맞춰본다.
	 *
	 * <p>부분 조건({@code WHERE ...})까지 보도록 T-014의 헬퍼로 통일했다. 조건 인자가 null이면
	 * {@code WHERE} 절이 <b>없는</b> 것까지 단언하므로, 조건이 새로 붙는 변이도 걸린다.
	 *
	 * @param method {@code indexdef}의 {@code USING} 뒤에 나와야 하는 인덱스 종류(소문자)
	 * @param expectedColumns {@code indexdef} 괄호 안에 그대로 나타나야 하는 열 목록
	 * @param expectedPredicate 부분 인덱스의 조건. 부분 인덱스가 아니면 null
	 */
	private void assertIndex(
			String tableName, String indexName, String method, String expectedColumns,
			String expectedPredicate) {
		List<String> definitions = new JdbcTemplate(dataSource).queryForList(
				"SELECT indexdef FROM pg_indexes "
						+ "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
				String.class, tableName, indexName);

		assertThat(definitions)
				.as("%s 테이블에 인덱스 %s가 없다", tableName, indexName)
				.hasSize(1);

		String expectedTail = "USING " + method + " (" + expectedColumns + ")"
				+ (expectedPredicate == null ? "" : " WHERE " + expectedPredicate);
		assertThat(definitions.get(0))
				.as("인덱스 %s의 종류·열 구성·부분 조건 중 하나가 설계와 다르다", indexName)
				.endsWith(expectedTail);
	}

	/**
	 * 컬럼의 실제 타입을 못 박는다. VARCHAR는 길이까지 본다.
	 *
	 * <p>{@code udt_name}만으로는 {@code VARCHAR(30)}과 {@code VARCHAR(255)}가 둘 다
	 * {@code varchar}라 길이 변이가 새어 나간다(T-014). 문자열이 아닌 타입은 null을 기대해
	 * 숫자 컬럼이 문자열로 바뀌는 변이도 함께 잡는다.
	 */
	private void assertColumnType(
			String tableName, String columnName, String expectedUdtName, Integer expectedLength) {
		Map<String, Object> column = new JdbcTemplate(dataSource).queryForMap(
				"SELECT udt_name, character_maximum_length FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
				tableName, columnName);

		assertThat(column.get("udt_name"))
				.as("%s.%s의 타입이 설계와 다르다", tableName, columnName)
				.isEqualTo(expectedUdtName);
		assertThat(column.get("character_maximum_length"))
				.as("%s.%s의 길이가 설계와 다르다", tableName, columnName)
				.isEqualTo(expectedLength);
	}

	private Category category(short id, String code) {
		return new Category(id, code, "테스트-" + code, id);
	}
}
