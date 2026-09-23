package com.petgyebu.telo.config;

import com.petgyebu.telo.common.time.AppZone;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각의 단일 출처를 빈으로 노출한다(T-052가 정한 KST 규칙).
 *
 * <p>시각을 다루는 컴포넌트는 {@code Instant.now()}나 {@code OffsetDateTime.now()}를 직접
 * 부르지 않고 이 {@link Clock}을 주입받는다. 두 가지를 동시에 얻는다 — 기준 존이 KST로
 * 고정되고, 테스트가 고정 시계를 끼워 만료·기간 경계를 실제로 넘겨볼 수 있다.
 */
@Configuration
public class TimeConfig {

	@Bean
	Clock clock() {
		return AppZone.clock();
	}
}
