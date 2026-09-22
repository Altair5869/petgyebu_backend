package com.petgyebu.telo.push;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.budget.domain.BudgetPeriod;
import com.petgyebu.telo.budget.repository.BudgetPeriodRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.push.domain.DevicePlatform;
import com.petgyebu.telo.push.domain.PushDeviceToken;
import com.petgyebu.telo.push.domain.PushLog;
import com.petgyebu.telo.push.domain.PushThresholdType;
import com.petgyebu.telo.push.repository.PushDeviceTokenRepository;
import com.petgyebu.telo.push.repository.PushLogRepository;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
 * {@code push_device_tokens}·{@code push_logs} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지
 * 본다({@code RewardSchemaTest}와 같은 방식).
 *
 * <p>이 테이블들에서 무게가 실린 곳은 {@code push_logs}의 2열 유니크다. "기간당 1회, 재발송
 * 없음"을 DB가 보장하는데, <b>거부만 보면 열 구성을 틀려도 통과한다.</b> 그래서 세 방향을 함께
 * 본다.
 * <ol>
 *   <li>같은 기간·같은 임계값 재발송은 거부된다</li>
 *   <li>같은 기간에 {@code STRONG_WARNING}과 {@code OVER_BUDGET}은 각각 한 번씩 저장된다 —
 *       {@code threshold_type}이 키에서 빠지면 막힌다</li>
 *   <li>기간이 다르면 같은 임계값을 다시 보낼 수 있다 — {@code budget_period_id}가 키에서
 *       빠지면 막힌다</li>
 * </ol>
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("push_device_tokens·push_logs 스키마 제약 검증")
class PushSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BudgetPeriodRepository budgetPeriodRepository;

	@Autowired
	private PushDeviceTokenRepository pushDeviceTokenRepository;

	@Autowired
	private PushLogRepository pushLogRepository;

	@Autowired
	private DataSource dataSource;

	// ---------------------------------------------------------------- push_device_tokens

	@Test
	@DisplayName("같은 FCM 토큰이 두 사용자에게 붙을 수 없다 — UNIQUE (fcm_token)")
	void duplicateFcmTokenIsRejected() {
		// 기기를 넘겨받은 경우를 충돌로 감지하기 위한 제약이다. 없으면 이전 주인과 새 주인
		// 양쪽에 같은 토큰이 남아 남의 예산 경고가 남의 기기로 간다.
		User previousOwner = givenUser("token-uq-1");
		User newOwner = givenUser("token-uq-2");
		insertDeviceToken(previousOwner, "fcm-shared-device", "ANDROID");

		assertThatThrownBy(() -> insertDeviceToken(newOwner, "fcm-shared-device", "IOS"))
				.as("같은 FCM 토큰이 두 사용자에게 저장됐다. UNIQUE (fcm_token)이 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_push_device_tokens_fcm_token");
	}

	@Test
	@DisplayName("한 사용자가 기기를 여러 대 등록할 수 있다 — 토큰만 다르면 된다")
	void differentTokensForSameUserAreAccepted() {
		// 거부 케이스만 보면 UNIQUE를 (user_id)나 (user_id, fcm_token)에 잘못 걸어도 통과한다.
		// (user_id)에 걸리면 휴대폰과 태블릿을 함께 쓰는 사용자가 한쪽 알림을 못 받는다.
		User user = givenUser("token-uq-ok-1");

		assertThatCode(() -> {
			insertDeviceToken(user, "fcm-phone-1", "ANDROID");
			insertDeviceToken(user, "fcm-tablet-1", "ANDROID");
		})
				.as("한 사용자의 두 번째 기기가 거부됐다. UNIQUE가 user_id에 걸려 있을 가능성이 높다")
				.doesNotThrowAnyException();

		assertThat(count("SELECT count(*) FROM push_device_tokens WHERE user_id = ?", user.getId()))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("웹 푸시는 없다 — platform = 'WEB'은 거부된다")
	void webPlatformIsRejected() {
		// 웹 푸시를 구현하지 않기로 했다(Q9). CHECK에 WEB이 들어가면 등록은 되는데 발송
		// 경로가 없어 조용히 알림이 사라지는 사용자가 생긴다.
		User user = givenUser("token-check-web-1");

		assertThatThrownBy(() -> insertDeviceToken(user, "fcm-web-1", "WEB"))
				.as("platform = 'WEB'이 저장됐다. CHECK 목록에 WEB이 들어 있다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_push_device_tokens_platform");
	}

	@Test
	@DisplayName("ANDROID·IOS 두 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다")
	void definedPlatformsAreAccepted() {
		// CHECK에서 한 값을 빼도 거부 케이스는 통과한다. IOS가 빠지면 iOS 사용자 전원이
		// 토큰 등록 자체에 실패한다.
		User user = givenUser("token-check-ok-1");

		for (DevicePlatform platform : DevicePlatform.values()) {
			assertThatCode(
					() -> insertDeviceToken(user, "fcm-ok-" + platform.name(), platform.name()))
					.as("platform = '%s'가 거부됐다. CHECK 목록에서 빠졌다", platform)
					.doesNotThrowAnyException();
		}
	}

	@Test
	@DisplayName("사용자를 지우면 기기 토큰도 함께 지워진다 — ON DELETE CASCADE")
	void deletingUserCascadesToDeviceTokens() {
		User user = givenUser("token-cascade-1");
		insertDeviceToken(user, "fcm-cascade-1", "ANDROID");

		new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", user.getId());

		assertThat(count("SELECT count(*) FROM push_device_tokens WHERE user_id = ?", user.getId()))
				.as("users FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
	}

	@Test
	@DisplayName("PushDeviceToken 엔티티로 저장한 값이 그대로 내려간다 — 엔티티 경로")
	void pushDeviceTokenEntityPersistsFields() {
		// 나머지 단언은 전부 JDBC raw INSERT 경로다. 엔티티를 지나가는 경로가 없으면
		// @Enumerated(STRING)이 풀려 서수가 저장돼도, 생성자가 인자를 잘못 담아도 드러나지
		// 않는다. ddl-auto: validate는 컬럼의 존재와 타입만 보기 때문이다(트러블슈팅 20번).
		User user = givenUser("token-entity-1");
		OffsetDateTime now = OffsetDateTime.now(AppZone.clock());

		PushDeviceToken saved = pushDeviceTokenRepository.saveAndFlush(
				new PushDeviceToken(user, "fcm-entity-1", DevicePlatform.IOS, now));

		Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap(
				"SELECT user_id, fcm_token, platform, created_at, updated_at "
						+ "FROM push_device_tokens WHERE id = ?",
				saved.getId());

		assertThat(row.get("user_id")).isEqualTo(user.getId());
		assertThat(row.get("fcm_token"))
				.as("fcm_token이 넘긴 값과 다르다. 생성자 인자가 잘못 담겼다")
				.isEqualTo("fcm-entity-1");
		// 서수로 저장되면 '1'이 들어가고 CHECK 제약에 걸려 죽는다. 문자열인지 값으로 못 박는다.
		assertThat(row.get("platform"))
				.as("platform이 문자열로 저장되지 않았다. @Enumerated(STRING)이 빠졌다")
				.isEqualTo("IOS");
		// isNotNull()로는 엔티티가 넘긴 시각을 어긋나게 담아도(now.plusYears(1) 같은) 통과한다.
		// 넘긴 값과 맞춰 본다.
		assertThat(instantOf(row.get("created_at")))
				.as("created_at이 넘긴 시각과 다르다. DB DEFAULT가 발화했거나 값이 어긋나게 담겼다")
				.isEqualTo(truncated(now));
		assertThat(instantOf(row.get("updated_at")))
				.as("updated_at이 넘긴 시각과 다르다. 생성 직후에는 created_at과 같아야 한다")
				.isEqualTo(truncated(now));
	}

	// ---------------------------------------------------------------- push_logs

	@Test
	@DisplayName("같은 기간에 같은 임계값을 두 번 보낼 수 없다 — UNIQUE (budget_period_id, threshold_type)")
	void duplicatePushForSameThresholdIsRejected() {
		// Upstash 키가 유실되거나 배치가 중복 실행돼도 두 번째 발송은 여기서 막힌다.
		// Redis는 빠른 판정용이고 DB가 최종 방어선이다(Q13).
		BudgetPeriod period = givenBudgetPeriod("push-uq-1");
		insertPushLog(period, "STRONG_WARNING");

		assertThatThrownBy(() -> insertPushLog(period, "STRONG_WARNING"))
				.as("같은 기간·같은 임계값이 두 번 발송됐다. "
						+ "UNIQUE (budget_period_id, threshold_type)가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_push_logs_period_threshold");

		assertThat(count("SELECT count(*) FROM push_logs WHERE budget_period_id = ?",
				period.getId()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("같은 기간에 STRONG_WARNING과 OVER_BUDGET은 각각 한 번씩 저장된다")
	void bothThresholdsCoexistInSamePeriod() {
		// 유니크에서 threshold_type이 빠지면 여기가 막힌다. 90%를 지나 100%를 넘긴 달에
		// 초과 알림이 통째로 사라진다 — KPI "예산 초과 경고 확인율"의 분모가 비는 것이다.
		BudgetPeriod period = givenBudgetPeriod("push-uq-ok-1");

		insertPushLog(period, "STRONG_WARNING");
		insertPushLog(period, "OVER_BUDGET");

		assertThat(new JdbcTemplate(dataSource).queryForList(
				"SELECT threshold_type FROM push_logs WHERE budget_period_id = ? "
						+ "ORDER BY threshold_type",
				String.class, period.getId()))
				.as("같은 기간에 두 임계값이 각각 남지 않았다. 유니크에 threshold_type이 빠졌다")
				.containsExactly("OVER_BUDGET", "STRONG_WARNING");
	}

	@Test
	@DisplayName("기간이 다르면 같은 임계값을 다시 보낼 수 있다 — 매달 경고가 가능해야 한다")
	void sameThresholdIsAllowedInAnotherPeriod() {
		// 유니크에서 budget_period_id가 빠지면 여기가 막힌다. 9월에 한 번 경고를 받은
		// 사용자가 10월부터 영영 경고를 못 받는다.
		User user = givenUser("push-uq-ok-2");
		BudgetPeriod september = givenBudgetPeriod(user, LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30));
		BudgetPeriod october = givenBudgetPeriod(user, LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 31));

		insertPushLog(september, "OVER_BUDGET");

		assertThatCode(() -> insertPushLog(october, "OVER_BUDGET"))
				.as("다음 달 같은 임계값 발송이 거부됐다. 유니크에 budget_period_id가 빠졌다")
				.doesNotThrowAnyException();

		assertThat(count("SELECT count(*) FROM push_logs WHERE user_id = ?", user.getId()))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("같은 기간을 다른 user_id로 두 번 기록해도 거부된다 — 유니크에 user_id가 없다")
	void sameThresholdWithDifferentUserIdIsRejected() {
		// 유니크에 user_id를 더하는 변이(3열)를 동작으로 잡는 유일한 단언이다. 나머지 세
		// 방향(재발송 거부 / 같은 기간 두 임계값 / 다음 달 재발송)은 2열과 3열에서 결과가
		// 같아 3열 변이를 통과시킨다.
		//
		// push_logs에는 user_id와 budget_period_id의 일치를 강제하는 복합 FK도 CHECK도 없다.
		// 두 FK가 각각 걸려 있을 뿐이라 "남의 기간에 내 user_id로 쓰는" 행은 DB 수준에서
		// 만들어진다. 3열 유니크였다면 이 INSERT가 통과해, user_id만 달리한 중복 발송 기록이
		// 같은 기간에 남는다 — R-ENPLNB 결정 6의 "기간당 1회"가 "사용자별 기간당 1회"로 바뀐다.
		BudgetPeriod period = givenBudgetPeriod("push-uq-otheruser-1");
		User otherUser = givenUser("push-uq-otheruser-2");
		insertPushLog(period, "OVER_BUDGET");

		assertThatThrownBy(() -> new JdbcTemplate(dataSource).update(
				"INSERT INTO push_logs (user_id, budget_period_id, threshold_type, sent_at) "
						+ "VALUES (?, ?, ?, now())",
				otherUser.getId(), period.getId(), "OVER_BUDGET"))
				.as("user_id만 다른 중복 기록이 저장됐다. 유니크에 user_id가 들어가 있다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_push_logs_period_threshold");
	}

	@Test
	@DisplayName("푸시를 보내지 않는 상태는 저장할 수 없다 — CHECK 제약")
	void undefinedThresholdTypeIsRejected() {
		// 캐릭터 상태는 6종이지만 푸시는 그중 둘에서만 나간다. ANXIOUS까지 저장되면
		// 사용률 70%대에도 알림이 나가는 구현이 DB에서 걸리지 않는다.
		BudgetPeriod period = givenBudgetPeriod("push-check-1");

		assertThatThrownBy(() -> insertPushLog(period, "ANXIOUS"))
				.as("CHECK (threshold_type IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_push_logs_threshold_type");
	}

	@Test
	@DisplayName("명세에 있는 threshold_type 두 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다")
	void definedThresholdTypesAreAccepted() {
		// CHECK 목록에서 한 값을 빼도 거부 케이스는 통과한다. OVER_BUDGET이 빠지면
		// 예산 초과 알림 자체가 발송 불가가 된다.
		BudgetPeriod period = givenBudgetPeriod("push-check-ok-1");

		for (PushThresholdType thresholdType : PushThresholdType.values()) {
			assertThatCode(() -> insertPushLog(period, thresholdType.name()))
					.as("threshold_type = '%s'가 거부됐다. CHECK 목록에서 빠졌다", thresholdType)
					.doesNotThrowAnyException();
		}
	}

	@Test
	@DisplayName("사용자를 지우면 발송 기록도 함께 지워진다 — user_id FK의 ON DELETE CASCADE")
	void deletingUserCascadesToPushLogs() {
		BudgetPeriod period = givenBudgetPeriod("push-cascade-user-1");
		insertPushLog(period, "STRONG_WARNING");
		Long userId = period.getUser().getId();

		new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", userId);

		assertThat(count("SELECT count(*) FROM push_logs WHERE user_id = ?", userId))
				.as("user_id FK에 ON DELETE CASCADE가 없다")
				.isZero();
	}

	@Test
	@DisplayName("예산 기간만 지워도 발송 기록이 함께 지워진다 — budget_period_id FK의 ON DELETE CASCADE")
	void deletingBudgetPeriodCascadesToPushLogs() {
		// 두 FK를 각각 확인한다. 사용자 삭제는 budget_periods도 함께 지우므로 user_id 쪽
		// CASCADE만으로 결과가 같아진다. 기간만 지우는 이 경로가 없으면 budget_period_id 쪽이
		// 통째로 비어도 드러나지 않는다(트러블슈팅 19번).
		BudgetPeriod period = givenBudgetPeriod("push-cascade-period-1");
		insertPushLog(period, "OVER_BUDGET");

		new JdbcTemplate(dataSource).update("DELETE FROM budget_periods WHERE id = ?",
				period.getId());

		assertThat(count("SELECT count(*) FROM push_logs WHERE budget_period_id = ?",
				period.getId()))
				.as("budget_period_id FK에 ON DELETE CASCADE가 없다")
				.isZero();
	}

	@Test
	@DisplayName("PushLog 엔티티로 저장하면 read_at이 NULL이다 — 발송 직후는 읽지 않은 상태다")
	void pushLogEntityPersistsUnread() {
		// 엔티티 경로 단언이다. 생성자가 readAt에 sentAt을 넣어도 raw INSERT 단언은 전부
		// 통과한다. 그 경우 KPI "예산 초과 경고 확인율"이 항상 100%로 보고된다.
		BudgetPeriod period = givenBudgetPeriod("push-entity-1");
		OffsetDateTime sentAt = OffsetDateTime.now(AppZone.clock());

		PushLog saved = pushLogRepository.saveAndFlush(new PushLog(
				period.getUser(), period, PushThresholdType.OVER_BUDGET, sentAt));

		Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap(
				"SELECT user_id, budget_period_id, threshold_type, sent_at, read_at "
						+ "FROM push_logs WHERE id = ?",
				saved.getId());

		assertThat(row.get("user_id")).isEqualTo(period.getUser().getId());
		assertThat(row.get("budget_period_id"))
				.as("budget_period_id가 다르다. 생성자 인자 순서가 뒤바뀌었다")
				.isEqualTo(period.getId());
		// 서수로 저장되면 '1'이 들어가고 CHECK 제약에 걸려 죽는다. 문자열인지 값으로 못 박는다.
		assertThat(row.get("threshold_type"))
				.as("threshold_type이 문자열로 저장되지 않았다. @Enumerated(STRING)이 빠졌다")
				.isEqualTo("OVER_BUDGET");
		// isNotNull()로는 생성자가 sentAt을 어긋나게 담아도(plusDays(1) 같은) 통과한다.
		// 발송 시각은 "기간당 1회" 판정과 KPI 집계가 그대로 읽는 값이라 넘긴 값과 맞춰 본다.
		assertThat(instantOf(row.get("sent_at")))
				.as("sent_at이 넘긴 시각과 다르다. 생성자가 값을 어긋나게 담았다")
				.isEqualTo(truncated(sentAt));
		assertThat(row.get("read_at"))
				.as("발송 직후인데 read_at이 채워져 있다. 확인율 KPI가 항상 100%%가 된다")
				.isNull();
		assertThat(saved.getReadAt()).isNull();
	}

	@Test
	@DisplayName("read_at은 나중에 채울 수 있다 — NULL 허용 컬럼이다")
	void readAtIsNullableAndFillable() {
		// NOT NULL이 붙으면 발송 자체가 불가능해진다. 나중에 채워지는 것도 함께 본다.
		BudgetPeriod period = givenBudgetPeriod("push-readat-1");
		insertPushLog(period, "STRONG_WARNING");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		jdbc.update("UPDATE push_logs SET read_at = now() WHERE budget_period_id = ?",
				period.getId());

		assertThat(jdbc.queryForObject(
				"SELECT read_at FROM push_logs WHERE budget_period_id = ?", OffsetDateTime.class,
				period.getId()))
				.as("열람 시각이 기록되지 않았다")
				.isNotNull();
	}

	// ---------------------------------------------------------------- 구조

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성까지")
	void designedIndexesExist() {
		assertIndex("push_device_tokens", "ix_push_device_tokens_user_id", "btree", "user_id",
				null);
		assertIndex("push_device_tokens", "uq_push_device_tokens_fcm_token", "btree", "fcm_token",
				null);
		// 열 순서까지 본다. reward_grants와 달리 user_id가 없는 2열이다.
		assertIndex("push_logs", "uq_push_logs_period_threshold", "btree",
				"budget_period_id, threshold_type", null);
	}

	@Test
	@DisplayName("컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지")
	void columnTypesMatchDesign() {
		assertColumnType("push_device_tokens", "id", "int8", null);
		assertColumnType("push_device_tokens", "user_id", "int8", null);
		// FCM 등록 토큰은 150자를 넘는 것이 흔하다. 길이가 줄면 토큰이 잘려 발송이 전부 실패한다.
		assertColumnType("push_device_tokens", "fcm_token", "varchar", 512);
		assertColumnType("push_device_tokens", "platform", "varchar", 10);
		assertColumnType("push_device_tokens", "created_at", "timestamptz", null);
		assertColumnType("push_device_tokens", "updated_at", "timestamptz", null);

		assertColumnType("push_logs", "id", "int8", null);
		assertColumnType("push_logs", "user_id", "int8", null);
		assertColumnType("push_logs", "budget_period_id", "int8", null);
		// STRONG_WARNING이 14자라 길이가 그 아래로 줄면 값이 잘린다.
		assertColumnType("push_logs", "threshold_type", "varchar", 20);
		assertColumnType("push_logs", "sent_at", "timestamptz", null);
		assertColumnType("push_logs", "read_at", "timestamptz", null);
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다 — read_at만 NULL 허용이다")
	void nullabilityMatchesDesign() {
		assertNullability("push_device_tokens", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("fcm_token", "NO"),
				entry("platform", "NO"),
				entry("created_at", "NO"),
				entry("updated_at", "NO")));

		assertNullability("push_logs", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("budget_period_id", "NO"),
				entry("threshold_type", "NO"),
				entry("sent_at", "NO"),
				// 발송 직후에는 읽지 않은 상태다. NOT NULL이 붙으면 발송 자체가 막힌다.
				entry("read_at", "YES")));
	}

	// ---------------------------------------------------------------- 헬퍼

	/** {@code RewardSchemaTest.assertColumnType}과 같은 취지다. VARCHAR는 길이까지 본다. */
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

	/** {@code RewardSchemaTest.assertNullability}와 같은 취지다. */
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

	/** {@code RewardSchemaTest.assertIndex}와 같은 취지다. */
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

	/**
	 * {@code TIMESTAMPTZ}를 드라이버가 {@link Timestamp}로 줄 수도 {@link OffsetDateTime}으로
	 * 줄 수도 있어 절대 시각으로 맞춘다.
	 */
	private static Instant instantOf(Object value) {
		if (value instanceof OffsetDateTime offsetDateTime) {
			return truncate(offsetDateTime.toInstant());
		}
		return truncate(((Timestamp) value).toInstant());
	}

	/**
	 * 밀리초로 자른다. JDK 시계와 PostgreSQL의 저장 정밀도가 달라(나노 대 마이크로) 그대로
	 * 비교하면 값이 맞아도 어긋난다. 검출하려는 것은 날·해 단위로 밀린 시각이라 이 정도면 된다.
	 */
	private static Instant truncated(OffsetDateTime value) {
		return truncate(value.toInstant());
	}

	private static Instant truncate(Instant value) {
		return value.truncatedTo(ChronoUnit.MILLIS);
	}

	private void insertDeviceToken(User user, String fcmToken, String platform) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO push_device_tokens (user_id, fcm_token, platform) VALUES (?, ?, ?)",
				user.getId(), fcmToken, platform);
	}

	/** {@code sent_at}에는 DB DEFAULT가 없어 항상 값을 넘긴다. */
	private void insertPushLog(BudgetPeriod period, String thresholdType) {
		new JdbcTemplate(dataSource).update(
				"INSERT INTO push_logs (user_id, budget_period_id, threshold_type, sent_at) "
						+ "VALUES (?, ?, ?, now())",
				period.getUser().getId(), period.getId(), thresholdType);
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private BudgetPeriod givenBudgetPeriod(String providerUserId) {
		return givenBudgetPeriod(givenUser(providerUserId), LocalDate.of(2026, 9, 1),
				LocalDate.of(2026, 9, 30));
	}

	/** 같은 사용자의 서로 다른 기간이 필요할 때 쓴다. {@code UNIQUE (user_id, period_start)}가 있다. */
	private BudgetPeriod givenBudgetPeriod(User user, LocalDate start, LocalDate end) {
		return budgetPeriodRepository.saveAndFlush(new BudgetPeriod(
				user, start, end, 500_000L, OffsetDateTime.now(AppZone.clock())));
	}

	private int count(String sql, Long id) {
		Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
