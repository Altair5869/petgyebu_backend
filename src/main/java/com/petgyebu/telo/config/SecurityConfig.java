package com.petgyebu.telo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Sprint 0 최소 설정. Cloud Run 헬스체크가 인증 없이 /actuator/health를 호출할 수 있도록 열어둔다.
 * 실제 인증 규칙은 Sprint 0의 소셜 로그인 작업에서 채운다.
 *
 * HTTP Basic은 켜지 않는다. 켜두면 Spring Boot가 자동 생성하는 user 계정이 살아 있는 자격증명이
 * 되고, 그 비밀번호가 표준출력에 찍혀 Cloud Logging에 남는다. 인증 방식은 Q4에서 확정한 무상태
 * JWT이므로 세션도 만들지 않고 CSRF도 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// 인증 수단이 없으면 기본 엔트리포인트가 403을 내므로 401로 고정한다.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.build();
	}
}
