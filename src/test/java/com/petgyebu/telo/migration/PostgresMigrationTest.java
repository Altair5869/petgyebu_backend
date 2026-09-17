package com.petgyebu.telo.migration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 운영 프로필을 실제 PostgreSQL 대상으로 검증한다.
 *
 * <p>{@code TeloApplicationTests}는 {@code local} 프로필(H2)로 돌기 때문에 Flyway와
 * {@code ddl-auto: validate}가 한 번도 실행되지 않는다. 그 상태에서는 엔티티만 추가하고
 * 마이그레이션을 빠뜨려도 {@code ./gradlew test}가 통과하고, PostgreSQL에 배포하는 시점에
 * {@code SchemaManagementException: Schema validation: missing table}로 처음 죽는다.
 * PR은 초록불인데 배포에서 터지는 구조라 이 테스트로 막는다.
 *
 * <p>프로필을 지정하지 않아 기본 프로필({@code application.yaml})로 뜬다. 즉 Flyway 활성,
 * {@code ddl-auto: validate} 상태 그대로다. 데이터소스만 {@code @ServiceConnection}이
 * 컨테이너로 바꿔치기한다.
 *
 * <p><b>Docker가 필요하다.</b> Docker 없이 {@code ./gradlew build}를 돌리면 이 테스트가
 * 실패한다. 조건부 스킵을 넣지 않은 것은 의도적이다. 스킵되면 CI가 초록불인데 정작 막으려던
 * 검증이 사라져, 이 테스트를 만든 이유 자체가 없어진다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("운영 프로필(PostgreSQL + Flyway + validate) 기동 검증")
class PostgresMigrationTest {

	// Testcontainers 2.x에서 org.testcontainers.containers.PostgreSQLContainer는 deprecated이고
	// 제네릭도 사라졌다. 새 좌표는 org.testcontainers.postgresql 패키지다.
	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("Flyway가 실제로 실행되고 Hibernate validate를 통과한다")
	void flywayRunsAndHibernateValidates() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		// 이 테스트가 뜬 것 자체가 ddl-auto: validate 통과를 뜻한다. 엔티티에 대응하는
		// 테이블이 없으면 컨텍스트 로딩 단계에서 SchemaManagementException으로 죽는다.

		// Flyway가 돌았는지는 이력 테이블 존재로 확인한다. spring-boot-flyway 의존성이
		// 빠지면 Flyway는 예외도 경고도 없이 아무것도 하지 않으므로, 컨텍스트가 뜨는 것만으로는
		// 판별할 수 없다. 이 단언이 그 조용한 실패를 잡는다.
		Integer historyTableCount = jdbc.queryForObject(
				"SELECT count(*) FROM information_schema.tables "
						+ "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
				Integer.class);

		assertThat(historyTableCount)
				.as("flyway_schema_history 테이블이 없다. spring-boot-flyway 의존성이 빠졌거나 "
						+ "spring.flyway.enabled가 꺼졌을 가능성이 높다")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("적용된 마이그레이션이 모두 성공 상태다")
	void allAppliedMigrationsSucceeded() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		Integer failedCount = jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

		assertThat(failedCount)
				.as("실패한 마이그레이션이 남아 있다")
				.isZero();
	}
}
