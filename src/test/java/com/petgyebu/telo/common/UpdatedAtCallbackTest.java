package com.petgyebu.telo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.petgyebu.telo.account.domain.Account;
import com.petgyebu.telo.account.repository.AccountRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code updated_at}을 가진 엔티티가 수정 시 그 값을 실제로 갱신하는지 본다.
 *
 * <p>가로지르는 관심사라 도메인별 스키마 테스트가 아니라 여기에 둔다. DB의
 * {@code DEFAULT now()}는 INSERT에만 발화하므로, {@code @PreUpdate} 콜백이
 * 없으면 수정 경로가 생기는 순간 여섯 테이블의 {@code updated_at}이 생성 시각에
 * 고정된다. 빌드도 {@code validate}도 통과하므로 아무도 눈치채지 못한다.
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("updated_at 갱신 콜백")
class UpdatedAtCallbackTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("엔티티를 수정하면 updated_at이 갱신된다 — created_at은 그대로다")
	void updatingEntityRefreshesUpdatedAt() {
		OffsetDateTime past = OffsetDateTime.now(AppZone.clock()).minusDays(3);
		User user = userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, "audit-" + System.nanoTime(), null, past));
		Account saved = accountRepository.saveAndFlush(
				new Account(user, "004", "connected-audit", "1234-**-5678", null, null, past));

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		OffsetDateTime createdBefore = readColumn(jdbc, "created_at", saved.getId());
		OffsetDateTime updatedBefore = readColumn(jdbc, "updated_at", saved.getId());
		assertThat(updatedBefore)
				.as("사전 조건: 생성 직후에는 두 값이 같아야 한다")
				.isEqualTo(createdBefore);

		// 엔티티에 아직 상태 전이 메서드가 없다(뒤 Task 몫). 콜백이 도는지만 보면 되므로
		// 리플렉션으로 필드를 바꿔 더티 체킹을 일으킨다. T-041에서 updatable=false를
		// 리플렉션으로 단언한 것과 같은 선례다.
		Account managed = accountRepository.findById(saved.getId()).orElseThrow();
		setField(managed, "accountName", "이름 변경으로 더티 체킹 유발");
		accountRepository.saveAndFlush(managed);

		OffsetDateTime createdAfter = readColumn(jdbc, "created_at", saved.getId());
		OffsetDateTime updatedAfter = readColumn(jdbc, "updated_at", saved.getId());

		assertThat(updatedAfter)
				.as("수정했는데 updated_at이 그대로다. @PreUpdate 콜백이 돌지 않았다")
				.isAfter(updatedBefore);
		assertThat(createdAfter)
				.as("created_at이 수정으로 바뀌었다. updatable = false가 풀렸다")
				.isEqualTo(createdBefore);
	}

	@Test
	@DisplayName("updated_at을 가진 엔티티 여섯 개가 전부 @PreUpdate 콜백을 갖는다")
	void everyEntityWithUpdatedAtHasCallback() {
		List<Class<?>> entities = List.of(
				com.petgyebu.telo.account.domain.Account.class,
				com.petgyebu.telo.budget.domain.BudgetPeriod.class,
				com.petgyebu.telo.budget.domain.StatusThreshold.class,
				com.petgyebu.telo.push.domain.PushDeviceToken.class,
				com.petgyebu.telo.reward.domain.CreditBalance.class,
				com.petgyebu.telo.transaction.domain.Transaction.class);

		for (Class<?> entity : entities) {
			boolean hasUpdatedAt = java.util.Arrays.stream(entity.getDeclaredFields())
					.anyMatch(f -> f.getName().equals("updatedAt"));
			assertThat(hasUpdatedAt)
					.as("%s에 updatedAt 필드가 없다. 목록이 낡았다", entity.getSimpleName())
					.isTrue();

			boolean hasCallback = java.util.Arrays.stream(entity.getDeclaredMethods())
					.anyMatch(m -> m.isAnnotationPresent(jakarta.persistence.PreUpdate.class));
			assertThat(hasCallback)
					.as("%s에 @PreUpdate가 없다. 수정해도 updated_at이 생성 시각에 고정된다",
							entity.getSimpleName())
					.isTrue();
		}
	}

	private OffsetDateTime readColumn(JdbcTemplate jdbc, String column, Long id) {
		return jdbc.queryForObject(
				"SELECT " + column + " FROM accounts WHERE id = ?", OffsetDateTime.class, id);
	}

	private void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(name + " 필드를 바꿀 수 없다", ex);
		}
	}
}
