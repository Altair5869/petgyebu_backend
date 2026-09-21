package com.petgyebu.telo.reward;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.budget.domain.BudgetPeriod;
import com.petgyebu.telo.budget.repository.BudgetPeriodRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.reward.domain.CreditBalance;
import com.petgyebu.telo.reward.domain.RewardConditionType;
import com.petgyebu.telo.reward.domain.RewardGrant;
import com.petgyebu.telo.reward.repository.CreditBalanceRepository;
import com.petgyebu.telo.reward.repository.RewardGrantRepository;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.time.LocalDate;
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
 * {@code credit_balances}·{@code reward_grants} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지
 * 본다({@code TransactionSchemaTest}와 같은 방식).
 *
 * <p>이 두 테이블에서 처음 나오는 것이 둘 있다.
 * <ol>
 *   <li>{@code credit_balances}에 독립 PK가 없다 — {@code user_id}가 PK 겸 FK다</li>
 *   <li>{@code reward_grants}의 유니크는 <b>거부만이 아니라 허용</b>이 본질이다. 조건이 다르면
 *       같은 사용자·같은 기간에도 두 행이 남아 크레딧이 합산된다</li>
 * </ol>
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("credit_balances·reward_grants 스키마 제약 검증")
class RewardSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BudgetPeriodRepository budgetPeriodRepository;

	@Autowired
	private CreditBalanceRepository creditBalanceRepository;

	@Autowired
	private RewardGrantRepository rewardGrantRepository;

	@Autowired
	private DataSource dataSource;

	// ---------------------------------------------------------------- credit_balances

	@Test
	@DisplayName("한 사용자에 잔액 행은 하나뿐이다 — user_id가 곧 PK다")
	void creditBalanceIsOneRowPerUser() {
		// 다른 모든 테이블과 달리 여기에는 GENERATED ALWAYS AS IDENTITY가 없다. 독립 id를
		// 되살리면 같은 사용자에 잔액 행이 둘 생겨, 하나만 차감하는 구매가 크레딧을 복제한다.
		User user = givenUser("credit-pk-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		jdbc.update("INSERT INTO credit_balances (user_id, balance) VALUES (?, ?)",
				user.getId(), 100L);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO credit_balances (user_id, balance) VALUES (?, ?)",
				user.getId(), 200L))
				.as("같은 사용자로 잔액 행이 두 개 만들어졌다. user_id가 PK가 아니다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("credit_balances_pkey");
	}

	@Test
	@DisplayName("@MapsId 매핑이 user_id 하나를 PK 겸 FK로 쓴다 — 별도 id 컬럼이 생기지 않는다")
	void creditBalanceIdentifierIsUserId() {
		User user = givenUser("credit-mapsid-1");

		CreditBalance saved = creditBalanceRepository.saveAndFlush(
				new CreditBalance(user, 0L, OffsetDateTime.now(AppZone.clock())));

		assertThat(saved.getUserId())
				.as("@MapsId가 연관에서 식별자를 끌어오지 못했다")
				.isEqualTo(user.getId());
		assertThat(creditBalanceRepository.findById(user.getId()))
				.as("사용자 ID로 잔액을 찾을 수 없다. 식별자 타입이 Long이 아닐 가능성이 높다")
				.isPresent();
	}

	@Test
	@DisplayName("잔액은 음수가 될 수 없다 — CHECK (balance >= 0)")
	void negativeBalanceIsRejected() {
		User user = givenUser("credit-check-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		// 잔액 부족 구매의 마지막 방어선이다. 애플리케이션 검사와 SELECT FOR UPDATE가 뚫려도
		// 여기서 막힌다.
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO credit_balances (user_id, balance) VALUES (?, ?)",
				user.getId(), -1L))
				.as("잔액이 음수인 행이 저장됐다. CHECK (balance >= 0)가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_credit_balances_balance_non_negative");
	}

	@Test
	@DisplayName("잔액 0과 양수는 저장된다 — CHECK가 과도하게 좁지 않다")
	void zeroAndPositiveBalancesAreAccepted() {
		// 거부 케이스만 보면 CHECK를 balance > 0으로 잘못 써도 통과한다. 그 경우 잔액을 전부
		// 쓴 사용자의 행을 갱신할 수 없게 되고 구매가 영구히 실패한다.
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		User zeroUser = givenUser("credit-check-ok-0");
		User positiveUser = givenUser("credit-check-ok-1");

		assertThatCode(() -> {
			jdbc.update("INSERT INTO credit_balances (user_id, balance) VALUES (?, ?)",
					zeroUser.getId(), 0L);
			jdbc.update("INSERT INTO credit_balances (user_id, balance) VALUES (?, ?)",
					positiveUser.getId(), 300L);
		})
				.as("잔액 0 또는 양수가 거부됐다. CHECK가 balance > 0으로 적혀 있을 가능성이 높다")
				.doesNotThrowAnyException();

		assertThat(jdbc.queryForObject(
				"SELECT balance FROM credit_balances WHERE user_id = ?", Long.class,
				zeroUser.getId()))
				.isZero();
	}

	@Test
	@DisplayName("사용자를 지우면 잔액 행도 함께 지워진다 — ON DELETE CASCADE")
	void deletingUserCascadesToCreditBalance() {
		User user = givenUser("credit-cascade-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.update("INSERT INTO credit_balances (user_id, balance) VALUES (?, ?)",
				user.getId(), 500L);

		jdbc.update("DELETE FROM users WHERE id = ?", user.getId());

		assertThat(count("SELECT count(*) FROM credit_balances WHERE user_id = ?", user.getId()))
				.as("users FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
	}

	// ---------------------------------------------------------------- reward_grants

	@Test
	@DisplayName("같은 사용자·기간·조건으로 두 번 지급할 수 없다 — UNIQUE")
	void duplicateGrantForSameConditionIsRejected() {
		BudgetPeriod period = givenBudgetPeriod("grant-uq-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		insertGrant(period, "WITHIN_TARGET", 100L);

		// 판정 배치가 재실행돼도 두 번 지급되지 않아야 한다.
		assertThatThrownBy(() -> insertGrant(period, "WITHIN_TARGET", 100L))
				.as("동일 조건이 두 번 지급됐다. UNIQUE (user_id, budget_period_id, condition_type)가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_reward_grants_user_period_condition");

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM reward_grants WHERE budget_period_id = ?", Integer.class,
				period.getId()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("조건이 다르면 같은 사용자·같은 기간에도 두 행이 남는다 — 합산 300크레딧")
	void grantsForDifferentConditionsCoexist() {
		// 유니크 키에서 condition_type이 빠지면 두 번째 지급이 거부되어, 두 조건을 모두 충족한
		// 달에 200크레딧이 통째로 사라진다. 거부 케이스만 보면 이 손실을 못 잡는다.
		BudgetPeriod period = givenBudgetPeriod("grant-uq-ok-1");

		insertGrant(period, "WITHIN_TARGET", 100L);
		insertGrant(period, "SAVED_10_PERCENT", 200L);

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT condition_type, credit_amount FROM reward_grants "
						+ "WHERE budget_period_id = ? ORDER BY condition_type",
				period.getId());

		assertThat(rows)
				.as("조건별 지급 기록이 두 행으로 남지 않았다")
				.hasSize(2)
				.extracting(row -> row.get("condition_type"))
				.containsExactly("SAVED_10_PERCENT", "WITHIN_TARGET");
		assertThat(jdbc.queryForObject(
				"SELECT sum(credit_amount) FROM reward_grants WHERE budget_period_id = ?",
				Long.class, period.getId()))
				.as("두 조건을 모두 충족한 달의 합산 크레딧이 300이 아니다")
				.isEqualTo(300L);
	}

	@Test
	@DisplayName("RewardGrant 엔티티로 저장한 값이 그대로 내려간다 — 판정 근거 두 컬럼까지")
	void rewardGrantEntityPersistsJudgementBasis() {
		// 나머지 reward_grants 단언은 전부 JDBC raw INSERT 경로다. 엔티티를 지나가는 경로가
		// 하나도 없으면 생성자 인자 순서가 뒤바뀌어도, @Enumerated(STRING)이 풀려 서수가
		// 저장돼도 아무도 모른다. ddl-auto: validate는 컬럼의 존재와 타입만 보기 때문이다.
		// 이 값들은 T-043의 "왜 보상을 못 받았는지" 조회가 그대로 읽는다.
		BudgetPeriod period = givenBudgetPeriod("grant-entity-1");

		RewardGrant saved = rewardGrantRepository.saveAndFlush(new RewardGrant(
				period.getUser(),
				period,
				RewardConditionType.SAVED_10_PERCENT,
				200L,
				360_000L,
				400_000L,
				OffsetDateTime.now(AppZone.clock())));

		Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap(
				"SELECT condition_type, credit_amount, period_expense_total, "
						+ "previous_period_expense_total FROM reward_grants WHERE id = ?",
				saved.getId());

		// 서수로 저장되면 '1'이 들어가고 CHECK 제약에 걸려 죽는다. 문자열인지 값으로 못 박는다.
		assertThat(row.get("condition_type"))
				.as("condition_type이 문자열로 저장되지 않았다. @Enumerated(STRING)이 빠졌다")
				.isEqualTo("SAVED_10_PERCENT");
		assertThat(row.get("credit_amount")).isEqualTo(200L);

		// 판정 근거 두 값은 크기가 다를 뿐 타입이 같아 뒤바뀌어도 예외가 나지 않는다.
		// 뒤바뀌면 "직전보다 덜 썼다"는 근거가 통째로 거꾸로 표시된다.
		assertThat(row.get("period_expense_total"))
				.as("해당 기간 지출 합계가 넘긴 값과 다르다. 생성자 인자 순서가 뒤바뀌었다")
				.isEqualTo(360_000L);
		assertThat(row.get("previous_period_expense_total"))
				.as("직전 기간 지출 합계가 넘긴 값과 다르다. 생성자 인자 순서가 뒤바뀌었다")
				.isEqualTo(400_000L);
	}

	@Test
	@DisplayName("첫 기간은 직전 기간 지출이 NULL로 내려간다 — 엔티티 경로")
	void firstPeriodGrantKeepsPreviousTotalNull() {
		// 첫 기간은 비교 대상이 없다. 엔티티가 null을 0으로 바꿔 넣으면 "직전 기간에 0원을
		// 썼다"가 되어 절약 판정 근거가 뒤집힌다.
		BudgetPeriod period = givenBudgetPeriod("grant-entity-null-1");

		RewardGrant saved = rewardGrantRepository.saveAndFlush(new RewardGrant(
				period.getUser(),
				period,
				RewardConditionType.WITHIN_TARGET,
				100L,
				360_000L,
				null,
				OffsetDateTime.now(AppZone.clock())));

		assertThat(new JdbcTemplate(dataSource).queryForObject(
				"SELECT previous_period_expense_total FROM reward_grants WHERE id = ?",
				Long.class, saved.getId()))
				.as("첫 기간인데 직전 기간 지출이 NULL이 아니다")
				.isNull();
	}

	@Test
	@DisplayName("정의되지 않은 condition_type은 저장할 수 없다 — CHECK 제약")
	void undefinedConditionTypeIsRejected() {
		BudgetPeriod period = givenBudgetPeriod("grant-check-1");

		assertThatThrownBy(() -> insertGrant(period, "SAVED_20_PERCENT", 400L))
				.as("CHECK (condition_type IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_reward_grants_condition_type");
	}

	@Test
	@DisplayName("명세에 있는 condition_type 두 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다")
	void definedConditionTypesAreAccepted() {
		// CHECK 목록에서 값을 하나 빼도 거부 케이스는 통과한다. SAVED_10_PERCENT가 빠지면
		// 절약 보상 자체가 지급 불가가 된다.
		BudgetPeriod period = givenBudgetPeriod("grant-check-ok-1");

		for (RewardConditionType conditionType : RewardConditionType.values()) {
			assertThatCode(() -> insertGrant(period, conditionType.name(), 100L))
					.as("condition_type = '%s'가 거부됐다. CHECK 목록에서 빠졌다", conditionType)
					.doesNotThrowAnyException();
		}
	}

	@Test
	@DisplayName("사용자를 지우면 지급 기록도 함께 지워진다 — user_id FK의 ON DELETE CASCADE")
	void deletingUserCascadesToRewardGrants() {
		BudgetPeriod period = givenBudgetPeriod("grant-cascade-user-1");
		insertGrant(period, "WITHIN_TARGET", 100L);
		Long userId = period.getUser().getId();

		new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", userId);

		assertThat(count("SELECT count(*) FROM reward_grants WHERE user_id = ?", userId))
				.as("user_id FK에 ON DELETE CASCADE가 없다")
				.isZero();
	}

	@Test
	@DisplayName("예산 기간을 지우면 지급 기록도 함께 지워진다 — budget_period_id FK의 ON DELETE CASCADE")
	void deletingBudgetPeriodCascadesToRewardGrants() {
		// 두 FK를 각각 확인한다. 한쪽만 보면 다른 쪽이 통째로 비어도 통과한다(트러블슈팅 19번).
		// 사용자 삭제는 budget_periods도 함께 지우므로 user_id 쪽 CASCADE만으로도 결과가 같아,
		// 기간만 지우는 이 경로가 없으면 budget_period_id 쪽은 무방비여도 드러나지 않는다.
		BudgetPeriod period = givenBudgetPeriod("grant-cascade-period-1");
		insertGrant(period, "WITHIN_TARGET", 100L);

		new JdbcTemplate(dataSource).update("DELETE FROM budget_periods WHERE id = ?",
				period.getId());

		assertThat(count("SELECT count(*) FROM reward_grants WHERE budget_period_id = ?",
				period.getId()))
				.as("budget_period_id FK에 ON DELETE CASCADE가 없다")
				.isZero();
	}

	// ---------------------------------------------------------------- 구조

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지")
	void designedIndexesExist() {
		// PK 인덱스가 user_id 하나로 이뤄져 있다는 것이 "독립 PK가 없다"의 구조적 증거다.
		assertIndex("credit_balances", "credit_balances_pkey", "btree", "user_id", null);
		assertIndex("reward_grants", "uq_reward_grants_user_period_condition", "btree",
				"user_id, budget_period_id, condition_type", null);
	}

	@Test
	@DisplayName("컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지")
	void columnTypesMatchDesign() {
		assertColumnType("credit_balances", "user_id", "int8", null);
		assertColumnType("credit_balances", "balance", "int8", null);
		assertColumnType("credit_balances", "updated_at", "timestamptz", null);

		assertColumnType("reward_grants", "id", "int8", null);
		assertColumnType("reward_grants", "user_id", "int8", null);
		assertColumnType("reward_grants", "budget_period_id", "int8", null);
		assertColumnType("reward_grants", "credit_amount", "int8", null);
		assertColumnType("reward_grants", "period_expense_total", "int8", null);
		assertColumnType("reward_grants", "previous_period_expense_total", "int8", null);
		assertColumnType("reward_grants", "granted_at", "timestamptz", null);

		// VARCHAR는 길이까지 본다. udt_name은 길이가 달라도 'varchar'라 길이 변이를 못 잡는다.
		// SAVED_10_PERCENT가 16자라 길이가 20 아래로 줄면 값이 잘린다.
		assertColumnType("reward_grants", "condition_type", "varchar", 30);
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다 — credit_balances에 id가 없는 것까지")
	void nullabilityMatchesDesign() {
		// 맵을 통째로 비교하므로 credit_balances에 독립 id 컬럼이 다시 붙는 것도 여기서 걸린다.
		assertNullability("credit_balances", Map.ofEntries(
				entry("user_id", "NO"),
				entry("balance", "NO"),
				entry("updated_at", "NO")));

		assertNullability("reward_grants", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("budget_period_id", "NO"),
				entry("condition_type", "NO"),
				entry("credit_amount", "NO"),
				entry("period_expense_total", "NO"),
				// 첫 기간은 직전 기간이 없다. NOT NULL이 붙으면 첫 달 판정이 통째로 막힌다.
				entry("previous_period_expense_total", "YES"),
				entry("granted_at", "NO")));
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

	/** {@code TransactionSchemaTest.assertIndex}와 같은 취지다. */
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
				.endsWith(expectedTail);
	}

	private void insertGrant(BudgetPeriod period, String conditionType, long creditAmount) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO reward_grants (user_id, budget_period_id, condition_type, "
						+ "credit_amount, period_expense_total, previous_period_expense_total) "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				period.getUser().getId(), period.getId(), conditionType, creditAmount,
				400_000L, null);
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private BudgetPeriod givenBudgetPeriod(String providerUserId) {
		return budgetPeriodRepository.saveAndFlush(new BudgetPeriod(
				givenUser(providerUserId),
				LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30),
				500_000L,
				OffsetDateTime.now(AppZone.clock())));
	}

	private int count(String sql, Long id) {
		Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
