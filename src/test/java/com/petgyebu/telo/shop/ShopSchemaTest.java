package com.petgyebu.telo.shop;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.shop.domain.AcquisitionType;
import com.petgyebu.telo.shop.domain.ItemType;
import com.petgyebu.telo.shop.domain.ShopItem;
import com.petgyebu.telo.shop.domain.UserItem;
import com.petgyebu.telo.shop.repository.ShopItemRepository;
import com.petgyebu.telo.shop.repository.UserItemRepository;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
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
 * {@code shop_items}·{@code user_items} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지 본다
 * ({@code TransactionSchemaTest}와 같은 방식).
 *
 * <p>핵심은 {@code user_items}의 <b>부분 유니크 인덱스</b>다. 거부만 보면 조건이 빠진 전체
 * 유니크로 바꿔도 통과하고, 그 상태에서는 보관함에 같은 종류 아이템을 여러 개 가질 수 없게
 * 된다. 그래서 거부와 허용을 양쪽 다 못 박는다.
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("shop_items·user_items 스키마 제약 검증")
class ShopSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ShopItemRepository shopItemRepository;

	@Autowired
	private UserItemRepository userItemRepository;

	@Autowired
	private DataSource dataSource;

	// ---------------------------------------------------------------- shop_items

	@Test
	@DisplayName("마이그레이션은 shop_items에 아무 행도 넣지 않는다")
	void shopItemsAreNotSeeded() {
		// 백로그의 "슬롯 4종 시드"는 item_type의 CHECK 값 4종을 가리키는 것이지 상점 아이템
		// 4개가 아니다. 실제 아이템의 code·name·image_url·price_credits가 어느 문서에도 없고,
		// image_url이 NOT NULL이라 이미지 에셋 없이는 채울 수도 없다. 지어낸 아이템이
		// 마이그레이션으로 들어오면 이 단언이 깨진다. 실제 시드는 T-044에서 정한다.
		// 이 테스트 클래스가 만드는 행은 code가 전부 't041-'로 시작한다(givenShopItem).
		// 그 밖의 행은 마이그레이션이 넣은 것뿐이므로, 테스트 실행 순서와 무관하게 판정된다.
		Integer seeded = new JdbcTemplate(dataSource).queryForObject(
				"SELECT count(*) FROM shop_items WHERE code NOT LIKE 't041-%'",
				Integer.class);

		assertThat(seeded)
				.as("shop_items에 시드 행이 들어와 있다. 근거 없는 아이템 정의가 추가됐다")
				.isZero();
	}

	@Test
	@DisplayName("같은 code로 아이템을 두 번 등록할 수 없다 — UNIQUE, 다른 code는 허용된다")
	void shopItemCodeIsUnique() {
		givenShopItem("wallpaper-a", ItemType.WALLPAPER, 100L);

		assertThatThrownBy(() -> givenShopItem("wallpaper-a", ItemType.FLOOR, 200L))
				.as("같은 code가 두 번 등록됐다. UNIQUE (code)가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_shop_items_code");

		// 허용 케이스. code가 아니라 (code, item_type) 같은 복합 유니크로 잘못 걸리면 여기는
		// 통과하지만 위 거부가 깨지고, 반대로 유니크가 과도하게 넓으면 여기가 깨진다.
		assertThatCode(() -> givenShopItem("wallpaper-b", ItemType.WALLPAPER, 100L))
				.as("code가 다른 아이템이 거부됐다. UNIQUE 범위가 너무 넓다")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("정의되지 않은 item_type은 저장할 수 없다 — CHECK 제약")
	void undefinedShopItemTypeIsRejected() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO shop_items (code, name, item_type, image_url, price_credits) "
						+ "VALUES (?, ?, ?, ?, ?)",
				"bad-type-1", "커튼", "CURTAIN", "gs://telo/items/curtain.png", 100L))
				.as("CHECK (item_type IN (...))가 없다. 슬롯 4종 밖의 값이 들어온다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_shop_items_item_type");
	}

	@Test
	@DisplayName("슬롯 4종은 전부 저장된다 — CHECK가 과도하게 좁지 않다")
	void allFourSlotsAreAccepted() {
		// CHECK 목록에서 값이 하나 빠지면 그 슬롯의 아이템을 아예 등록할 수 없다. 거부 케이스만
		// 보면 드러나지 않는다. 슬롯 4종은 방 꾸미기 렌더링 순서와 1:1이다.
		for (ItemType itemType : ItemType.values()) {
			assertThatCode(() -> givenShopItem("slot-" + itemType.name(), itemType, 100L))
					.as("item_type = '%s'가 거부됐다. CHECK 목록에서 빠졌다", itemType)
					.doesNotThrowAnyException();
		}

		assertThat(new JdbcTemplate(dataSource).queryForList(
				"SELECT item_type FROM shop_items WHERE code LIKE 't041-slot-%' "
						+ "ORDER BY item_type",
				String.class))
				.containsExactly("FLOOR", "HOUSE", "TOY", "WALLPAPER");
	}

	@Test
	@DisplayName("가격은 100~500 크레딧이다 — 경계값 100·500은 허용, 99·501은 거부")
	void priceCreditsMustBeWithinDesignedRange() {
		// BETWEEN은 양끝을 포함한다. 부등호를 하나라도 열고 닫는 방향을 틀리면 경계값에서만
		// 드러나므로 100·500을 따로 넣어 본다.
		assertThatCode(() -> {
			givenShopItem("price-100", ItemType.TOY, 100L);
			givenShopItem("price-500", ItemType.TOY, 500L);
		})
				.as("경계값 100 또는 500이 거부됐다. BETWEEN이 양끝을 포함하지 않는다")
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> givenShopItem("price-99", ItemType.TOY, 99L))
				.as("가격대 아래(99)가 저장됐다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_shop_items_price_credits");

		assertThatThrownBy(() -> givenShopItem("price-501", ItemType.TOY, 501L))
				.as("가격대 위(501)가 저장됐다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_shop_items_price_credits");
	}

	// ---------------------------------------------------------------- user_items

	@Test
	@DisplayName("같은 아이템을 두 번 살 수 없다 — UNIQUE (user_id, shop_item_id)")
	void duplicatePurchaseOfSameItemIsRejected() {
		User user = givenUser("useritem-uq-1");
		ShopItem item = givenShopItem("uq-item-1", ItemType.HOUSE, 200L);
		userItemRepository.saveAndFlush(purchase(user, item));

		assertThatThrownBy(() -> userItemRepository.saveAndFlush(purchase(user, item)))
				.as("같은 아이템이 두 번 보유 목록에 들어갔다. UNIQUE (user_id, shop_item_id)가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_user_items_user_shop_item");
	}

	@Test
	@DisplayName("다른 아이템·다른 사용자는 함께 보유된다 — UNIQUE가 과도하게 넓지 않다")
	void differentItemsAndUsersCoexist() {
		User user = givenUser("useritem-uq-ok-1");
		User other = givenUser("useritem-uq-ok-2");
		ShopItem first = givenShopItem("uq-ok-item-1", ItemType.HOUSE, 200L);
		ShopItem second = givenShopItem("uq-ok-item-2", ItemType.TOY, 200L);

		assertThatCode(() -> {
			userItemRepository.saveAndFlush(purchase(user, first));
			userItemRepository.saveAndFlush(purchase(user, second));
			// 같은 아이템이라도 사용자가 다르면 별개다.
			userItemRepository.saveAndFlush(purchase(other, first));
		})
				.as("아이템이나 사용자가 다른데도 거부됐다. UNIQUE 범위가 너무 넓다")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("배치된 같은 슬롯 아이템 두 개는 거부된다 — 부분 유니크 인덱스")
	void twoPlacedItemsInSameSlotAreRejected() {
		User user = givenUser("slot-uq-1");
		ShopItem first = givenShopItem("slot-uq-item-1", ItemType.WALLPAPER, 100L);
		ShopItem second = givenShopItem("slot-uq-item-2", ItemType.WALLPAPER, 100L);
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		insertUserItem(user, first, ItemType.WALLPAPER, true);

		// 슬롯당 1개 규칙을 DB가 보장한다. 이것이 없으면 동시 요청에서 벽지 두 개가 배치된
		// 상태가 만들어지고 메인 홈 렌더링이 무엇을 그릴지 정해지지 않는다.
		assertThatThrownBy(() -> insertUserItem(user, second, ItemType.WALLPAPER, true))
				.as("같은 슬롯에 배치된 아이템이 두 개 생겼다. 부분 유니크 인덱스가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_user_items_user_item_type_placed");

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM user_items WHERE user_id = ? AND is_placed = TRUE",
				Integer.class, user.getId()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("배치하지 않은 같은 슬롯 아이템은 여러 개 보유할 수 있다 — 부분 조건이 살아 있다")
	void multipleUnplacedItemsInSameSlotAreAllowed() {
		// 이 테스트가 완료 기준 2의 반대 방향이다. WHERE 절을 빼고 전체 유니크로 만들면 거부
		// 케이스는 그대로 통과하지만 여기가 깨진다. 보관함에 벽지를 여러 장 가질 수 없게 되어
		// 상점에서 두 번째 벽지를 사는 것 자체가 불가능해진다.
		User user = givenUser("slot-unplaced-1");
		ShopItem first = givenShopItem("slot-unplaced-item-1", ItemType.FLOOR, 100L);
		ShopItem second = givenShopItem("slot-unplaced-item-2", ItemType.FLOOR, 100L);
		ShopItem third = givenShopItem("slot-unplaced-item-3", ItemType.FLOOR, 100L);

		assertThatCode(() -> {
			insertUserItem(user, first, ItemType.FLOOR, false);
			insertUserItem(user, second, ItemType.FLOOR, false);
			insertUserItem(user, third, ItemType.FLOOR, false);
		})
				.as("배치하지 않은 같은 슬롯 아이템이 거부됐다. 유니크 인덱스에서 "
						+ "WHERE is_placed = TRUE 조건이 빠졌을 가능성이 높다")
				.doesNotThrowAnyException();

		assertThat(new JdbcTemplate(dataSource).queryForObject(
				"SELECT count(*) FROM user_items WHERE user_id = ? AND item_type = 'FLOOR'",
				Integer.class, user.getId()))
				.isEqualTo(3);

		// 보관함에 셋이 있어도 그 중 하나는 배치할 수 있어야 한다.
		assertThatCode(() -> new JdbcTemplate(dataSource).update(
				"UPDATE user_items SET is_placed = TRUE WHERE user_id = ? AND shop_item_id = ?",
				user.getId(), first.getId()))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("슬롯이 다르면 배치된 아이템이 여러 개여도 된다 — 방에 4슬롯이 모두 채워진다")
	void placedItemsInDifferentSlotsCoexist() {
		User user = givenUser("slot-four-1");

		assertThatCode(() -> {
			for (ItemType itemType : ItemType.values()) {
				ShopItem item = givenShopItem("four-" + itemType.name(), itemType, 100L);
				insertUserItem(user, item, itemType, true);
			}
		})
				.as("서로 다른 슬롯의 배치가 거부됐다. 유니크 열 구성에서 item_type이 빠졌을 수 있다")
				.doesNotThrowAnyException();

		assertThat(new JdbcTemplate(dataSource).queryForObject(
				"SELECT count(*) FROM user_items WHERE user_id = ? AND is_placed = TRUE",
				Integer.class, user.getId()))
				.as("메인 홈이 읽어야 할 배치 아이템 4개가 모이지 않았다")
				.isEqualTo(4);
	}

	@Test
	@DisplayName("정의되지 않은 item_type·acquisition_type은 저장할 수 없다 — CHECK 제약")
	void undefinedUserItemEnumValuesAreRejected() {
		User user = givenUser("useritem-check-1");
		ShopItem item = givenShopItem("useritem-check-item-1", ItemType.TOY, 100L);
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO user_items (user_id, shop_item_id, item_type, acquisition_type) "
						+ "VALUES (?, ?, ?, ?)",
				user.getId(), item.getId(), "CURTAIN", "PURCHASE"))
				.as("CHECK (item_type IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_user_items_item_type");

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO user_items (user_id, shop_item_id, item_type, acquisition_type) "
						+ "VALUES (?, ?, ?, ?)",
				user.getId(), item.getId(), "TOY", "REFUND"))
				.as("CHECK (acquisition_type IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_user_items_acquisition_type");
	}

	@Test
	@DisplayName("PURCHASE·GRANT 둘 다 저장된다 — CHECK가 과도하게 좁지 않다")
	void bothAcquisitionTypesAreAccepted() {
		// GRANT가 CHECK 목록에서 빠지면 이벤트 지급 경로가 통째로 막힌다. price_paid가 null인
		// 것도 함께 본다.
		User user = givenUser("useritem-check-ok-1");
		ShopItem purchased = givenShopItem("acq-ok-item-1", ItemType.TOY, 300L);
		ShopItem granted = givenShopItem("acq-ok-item-2", ItemType.HOUSE, 300L);
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		jdbc.update("INSERT INTO user_items (user_id, shop_item_id, item_type, "
						+ "acquisition_type, price_paid) VALUES (?, ?, ?, ?, ?)",
				user.getId(), purchased.getId(), "TOY", "PURCHASE", 300L);
		jdbc.update("INSERT INTO user_items (user_id, shop_item_id, item_type, "
						+ "acquisition_type, price_paid) VALUES (?, ?, ?, ?, ?)",
				user.getId(), granted.getId(), "HOUSE", "GRANT", null);

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT acquisition_type, price_paid FROM user_items WHERE user_id = ? "
						+ "ORDER BY acquisition_type",
				user.getId());

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("acquisition_type")).isEqualTo("GRANT");
		assertThat(rows.get(0).get("price_paid"))
				.as("지급 아이템에 지불 금액이 들어가 있다")
				.isNull();
		assertThat(rows.get(1).get("acquisition_type")).isEqualTo("PURCHASE");
		assertThat(rows.get(1).get("price_paid")).isEqualTo(300L);
	}

	@Test
	@DisplayName("사용자를 지우면 보유 아이템도 함께 지워진다 — user_id FK의 ON DELETE CASCADE")
	void deletingUserCascadesToUserItems() {
		User user = givenUser("useritem-cascade-1");
		ShopItem item = givenShopItem("cascade-item-1", ItemType.TOY, 100L);
		userItemRepository.saveAndFlush(purchase(user, item));

		new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", user.getId());

		assertThat(count("SELECT count(*) FROM user_items WHERE user_id = ?", user.getId()))
				.as("user_id FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
	}

	@Test
	@DisplayName("보유자가 있는 상점 아이템은 지워지지 않는다 — shop_item_id FK는 NO ACTION이다")
	void deletingOwnedShopItemIsRejected() {
		// 두 FK를 각각 확인한다(트러블슈팅 19번). 여기에 CASCADE를 따라 붙이면 상점에서 아이템을
		// 지우는 순간 이미 산 사람들의 보유가 조용히 사라진다. 판매 중단은 is_active = false다.
		User user = givenUser("shopitem-noaction-1");
		ShopItem item = givenShopItem("noaction-item-1", ItemType.TOY, 100L);
		userItemRepository.saveAndFlush(purchase(user, item));
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update("DELETE FROM shop_items WHERE id = ?", item.getId()))
				.as("보유자가 있는데도 상점 아이템이 삭제됐다. FK에 CASCADE가 붙어 있다")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count("SELECT count(*) FROM user_items WHERE shop_item_id = ?", item.getId()))
				.as("보유 기록이 사라졌다")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("user_items.item_type 매핑이 updatable = false다 — 비정규화 복사본은 갱신되지 않는다")
	void denormalizedItemTypeIsNotUpdatable() throws NoSuchFieldException {
		// shop_items에서 한 번 복사한 뒤 바꾸지 않는다는 규칙(docs/09-db-design.md 5.4절)이
		// 매핑에 박혀 있는지 본다. updatable이 풀리면 JPA가 UPDATE에 item_type을 실어 보내
		// 배치된 슬롯이 엔티티 변경만으로 바뀔 수 있다.
		Field field = UserItem.class.getDeclaredField("itemType");

		assertThat(field.getAnnotation(Column.class).updatable())
				.as("UserItem.itemType에 updatable = false가 없다")
				.isFalse();
	}

	@Test
	@DisplayName("UserItem은 shop_items의 item_type을 그대로 복사한다")
	void userItemCopiesItemTypeFromShopItem() {
		// 두 값이 어긋나면 슬롯당 1개 규칙이 엉뚱한 슬롯에 걸린다. 생성자가 인자로 받지 않고
		// shopItem에서 읽어오는지 확인한다.
		User user = givenUser("useritem-copy-1");
		ShopItem item = givenShopItem("copy-item-1", ItemType.HOUSE, 150L);

		UserItem saved = userItemRepository.saveAndFlush(purchase(user, item));

		assertThat(saved.getItemType()).isEqualTo(ItemType.HOUSE);
		assertThat(new JdbcTemplate(dataSource).queryForObject(
				"SELECT item_type FROM user_items WHERE id = ?", String.class, saved.getId()))
				.isEqualTo("HOUSE");
	}

	// ---------------------------------------------------------------- 구조

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·부분 조건까지")
	void designedIndexesExist() {
		assertIndex("shop_items", "uq_shop_items_code", "btree", "code", null);
		assertIndex("user_items", "uq_user_items_user_shop_item", "btree",
				"user_id, shop_item_id", null);

		// 부분 유니크 인덱스다. WHERE 절이 빠지면 전체 유니크가 되어 보관함의 같은 슬롯 중복
		// 보유가 막히는데, 거부 테스트만으로는 드러나지 않아 여기서 정의 자체를 못 박는다.
		// PostgreSQL이 정규화한 모양이 '(is_placed = true)'다.
		assertIndex("user_items", "uq_user_items_user_item_type_placed", "btree",
				"user_id, item_type", "(is_placed = true)");
	}

	@Test
	@DisplayName("컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지")
	void columnTypesMatchDesign() {
		assertColumnType("shop_items", "id", "int8", null);
		assertColumnType("shop_items", "price_credits", "int8", null);
		// sort_order가 int4로 바뀌어도 Hibernate validate는 문제 삼지 않는다(T-013).
		assertColumnType("shop_items", "sort_order", "int2", null);
		assertColumnType("shop_items", "is_active", "bool", null);
		assertColumnType("shop_items", "code", "varchar", 50);
		assertColumnType("shop_items", "name", "varchar", 100);
		assertColumnType("shop_items", "item_type", "varchar", 20);
		// Cloud Storage 경로가 들어가므로 길이가 넉넉해야 한다.
		assertColumnType("shop_items", "image_url", "varchar", 500);
		assertColumnType("shop_items", "description", "varchar", 255);

		assertColumnType("user_items", "id", "int8", null);
		assertColumnType("user_items", "user_id", "int8", null);
		assertColumnType("user_items", "shop_item_id", "int8", null);
		assertColumnType("user_items", "price_paid", "int8", null);
		assertColumnType("user_items", "acquired_at", "timestamptz", null);
		assertColumnType("user_items", "is_placed", "bool", null);
		assertColumnType("user_items", "item_type", "varchar", 20);
		assertColumnType("user_items", "acquisition_type", "varchar", 10);
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다")
	void nullabilityMatchesDesign() {
		assertNullability("shop_items", Map.ofEntries(
				entry("id", "NO"),
				entry("code", "NO"),
				entry("name", "NO"),
				entry("item_type", "NO"),
				entry("image_url", "NO"),
				entry("price_credits", "NO"),
				// 설명 문구는 없어도 된다.
				entry("description", "YES"),
				entry("sort_order", "NO"),
				entry("is_active", "NO")));

		assertNullability("user_items", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("shop_item_id", "NO"),
				entry("item_type", "NO"),
				entry("acquisition_type", "NO"),
				// 지급(GRANT)은 지불 금액이 없다. NOT NULL이 붙으면 지급 경로가 막힌다.
				entry("price_paid", "YES"),
				entry("acquired_at", "NO"),
				entry("is_placed", "NO")));
	}

	// ---------------------------------------------------------------- 헬퍼

	/** {@code TransactionSchemaTest.assertColumnType}과 같은 취지다. VARCHAR는 길이까지 본다. */
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

	/** {@code TransactionSchemaTest.assertNullability}와 같은 취지다. */
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

	/** {@code TransactionSchemaTest.assertIndex}와 같은 취지다. 부분 조건까지 본다. */
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
				.as("인덱스 %s의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다", indexName)
				// endsWith라서 조건이 더 붙거나 빠지는 것이 양쪽 다 걸린다.
				.endsWith(expectedTail);
	}

	private void insertUserItem(User user, ShopItem item, ItemType itemType, boolean placed) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO user_items (user_id, shop_item_id, item_type, acquisition_type, "
						+ "price_paid, is_placed) VALUES (?, ?, ?, ?, ?, ?)",
				user.getId(), item.getId(), itemType.name(), "PURCHASE",
				item.getPriceCredits(), placed);
	}

	private UserItem purchase(User user, ShopItem item) {
		return new UserItem(user, item, AcquisitionType.PURCHASE, item.getPriceCredits(),
				OffsetDateTime.now(AppZone.clock()));
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private ShopItem givenShopItem(String code, ItemType itemType, long priceCredits) {
		// 접두사를 붙여 마이그레이션이 넣은 행과 구분한다(shopItemsAreNotSeeded가 이것을 쓴다).
		return shopItemRepository.saveAndFlush(new ShopItem(
				"t041-" + code,
				"테스트 아이템 " + code,
				itemType,
				"gs://telo/items/" + code + ".png",
				priceCredits,
				null,
				(short) 0));
	}

	private int count(String sql, Long id) {
		Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
