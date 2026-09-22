package com.petgyebu.telo.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Configuration;

/**
 * 배치 메타데이터를 PostgreSQL에 저장하도록 JobRepository를 JDBC로 고정한다.
 *
 * <p><b>Spring Batch 6.0에서 기본값이 바뀌었다.</b> 5.x에서는 {@code @EnableBatchProcessing}과
 * Boot 자동설정이 DataSource를 보고 JDBC JobRepository를 만들어 줬다. 6.0의
 * {@code DefaultBatchConfiguration#jobRepository()}는 {@code ResourcelessJobRepository}를
 * 돌려준다. Boot 4의 {@code BatchAutoConfiguration}이 이 클래스를 그대로 상속하므로,
 * 아무것도 선언하지 않으면 DataSource가 있어도 <b>메타데이터가 메모리에만 남고 DB에는 한 줄도
 * 쓰이지 않는다.</b> Job은 정상적으로 COMPLETED로 끝나기 때문에 예외도 경고도 없다.
 * JDBC 기반으로 쓰려면 6.0에서 새로 생긴 {@code @EnableJdbcJobRepository}를 명시해야 한다.
 *
 * <p>{@code @EnableJdbcJobRepository}는 {@code @EnableBatchProcessing}이 붙은 설정 클래스에
 * 함께 써야 한다. 이 둘을 선언하면 Boot의 기본 배치 설정
 * ({@code @ConditionalOnMissingBean(annotation = EnableBatchProcessing.class)})은 물러난다.
 *
 * <p>기본값대로 {@code dataSource}·{@code transactionManager}·{@code jdbcTemplate} 빈을 쓴다.
 * 테이블은 Flyway가 만든다. Spring Boot 4.0에는 배치 스키마를 자동 생성하는 초기화기가 없다
 * — Boot 3.x의 {@code spring.batch.jdbc.initialize-schema}는 4.0에서 사라졌으므로 그 속성을
 * 근거로 삼지 마라. 마이그레이션은 {@code V202609221408__create_spring_batch_metadata.sql}이다.
 */
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository
public class BatchConfig {
}
