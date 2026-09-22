package com.petgyebu.telo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 PostgreSQL에서 배치 Job 하나를 끝까지 돌려 메타 테이블이 쓸 수 있는 상태인지 본다.
 *
 * <p>테이블이 만들어졌다는 것만으로는 부족하다. 컬럼 이름을 하나 잘못 옮겨 적어도
 * {@code CREATE TABLE}은 성공하고 {@code PostgresMigrationTest}도 통과한다. 그 오류는
 * {@code JdbcJobRepository}가 INSERT를 날리는 순간에야 드러난다. 시퀀스를 빠뜨린 경우도
 * 마찬가지로 DDL 단계에서는 전혀 티가 나지 않는다.
 *
 * <p>Job을 {@code @Bean}이 아니라 테스트 메서드 안에서 만든다. 빈으로 두면 Boot의
 * {@code JobLauncherApplicationRunner}가 컨텍스트 기동 중에 제멋대로 실행해, 이 테스트가
 * 무엇을 돌렸는지 불분명해진다.
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b> 다른 스키마 테스트와 같은 이유다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Spring Batch 메타 테이블 위에서 Job이 실제로 실행된다")
class BatchJobExecutionTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@Autowired
	private JobOperator jobOperator;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("Job이 COMPLETED로 끝나고 BATCH_JOB_EXECUTION에 기록이 남는다")
	void jobRunsToCompletionAndIsRecorded() throws Exception {
		Step step = new StepBuilder("t040-smoke-step", jobRepository)
				.tasklet((contribution, chunkContext) -> RepeatStatus.FINISHED, transactionManager)
				.build();
		Job job = new JobBuilder("t040-smoke-job", jobRepository)
				.start(step)
				.build();

		// 같은 파라미터로 두 번 돌면 JobInstanceAlreadyCompleteException이 난다. 매번 다른 값을 준다.
		JobParameters parameters = new JobParametersBuilder()
				.addLong("runAt", System.currentTimeMillis())
				.toJobParameters();

		JobExecution execution = jobOperator.start(job, parameters);

		assertThat(execution.getStatus())
				.as("Job이 COMPLETED로 끝나지 않았다. 메타 테이블의 컬럼이 원본과 다를 가능성이 높다")
				.isEqualTo(BatchStatus.COMPLETED);

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		// 인메모리 객체가 아니라 DB에 실제로 쓰였는지 확인한다.
		String persistedStatus = jdbc.queryForObject(
				"SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
				String.class, execution.getId());
		assertThat(persistedStatus)
				.as("BATCH_JOB_EXECUTION에 COMPLETED가 남아야 한다")
				.isEqualTo("COMPLETED");

		String persistedJobName = jdbc.queryForObject(
				"SELECT i.JOB_NAME FROM BATCH_JOB_INSTANCE i "
						+ "JOIN BATCH_JOB_EXECUTION e ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID "
						+ "WHERE e.JOB_EXECUTION_ID = ?",
				String.class, execution.getId());
		assertThat(persistedJobName).isEqualTo("t040-smoke-job");

		// StepExecution까지 기록돼야 BATCH_STEP_EXECUTION과 그 시퀀스도 살아 있다는 뜻이다.
		Integer stepCount = jdbc.queryForObject(
				"SELECT count(*) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ? AND STATUS = 'COMPLETED'",
				Integer.class, execution.getId());
		assertThat(stepCount)
				.as("BATCH_STEP_EXECUTION에 완료된 스텝이 남아야 한다")
				.isEqualTo(1);
	}
}
