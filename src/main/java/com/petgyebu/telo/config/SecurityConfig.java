package com.petgyebu.telo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Sprint 0 최소 설정. Cloud Run 헬스체크가 인증 없이 /actuator/health를 호출할 수 있도록 열어둔다.
 * 실제 인증 규칙은 Sprint 0의 소셜 로그인 작업에서 채운다.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.httpBasic(basic -> {
				})
				.build();
	}
}
