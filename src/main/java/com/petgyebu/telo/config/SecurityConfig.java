package com.petgyebu.telo.config;

import com.petgyebu.telo.auth.AccessTokenAuthenticationFilter;
import com.petgyebu.telo.auth.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Sprint 0 최소 설정. Cloud Run 헬스체크가 인증 없이 /actuator/health를 호출할 수 있도록 열어둔다.
 * 인증 수단은 T-003a에서 액세스 토큰 검증 필터까지 채웠다. 로그인 진입점(카카오 인가 코드
 * 교환, T-002)과 리프레시 토큰 경로(T-003b)는 아직 없다.
 *
 * HTTP Basic은 켜지 않는다. 켜두면 Spring Boot가 자동 생성하는 user 계정이 살아 있는 자격증명이
 * 되고, 그 비밀번호가 표준출력에 찍혀 Cloud Logging에 남는다. 인증 방식은 Q4에서 확정한 무상태
 * JWT이므로 세션도 만들지 않고 CSRF도 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, TokenService tokenService) throws Exception {
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
				// 액세스 토큰 필터(T-003a). 폼 로그인 자리인 UsernamePasswordAuthenticationFilter
				// 앞에 둔다. 필터를 빈으로 만들지 않고 여기서 직접 생성하는 이유: Filter 타입 빈은
				// Boot가 서블릿 컨테이너 필터로도 자동 등록해 시큐리티 체인 밖 요청에까지 걸린다.
				.addFilterBefore(new AccessTokenAuthenticationFilter(tokenService),
						UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
