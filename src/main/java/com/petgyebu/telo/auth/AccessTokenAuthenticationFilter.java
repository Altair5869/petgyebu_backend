package com.petgyebu.telo.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer} 헤더를 읽어 {@code SecurityContext}에 인증 주체를 올린다.
 *
 * <p><b>여기서 직접 401을 쓰지 않는다.</b> 토큰이 없거나 틀리면 컨텍스트를 비워둔 채 체인을
 * 계속 태우고, 인가 단계가 보호된 경로에 대해서만 401을 낸다({@code SecurityConfig}의
 * {@code HttpStatusEntryPoint}). 이렇게 해야 {@code /actuator/health}처럼 permitAll인 경로가
 * 이상한 헤더 하나 때문에 막히지 않는다.
 *
 * <p>인증 주체(principal)는 {@code users.id}인 {@code Long}이다. 컨트롤러는 경로에 userId를
 * 받지 않고 {@code @AuthenticationPrincipal Long userId}로 꺼낸다 — 경로에 있으면 남의 id를
 * 넣는 요청을 매번 막아야 한다.
 */
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

	private final TokenService tokenService;

	public AccessTokenAuthenticationFilter(TokenService tokenService) {
		this.tokenService = tokenService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String token = TokenService.extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
		if (token != null) {
			try {
				Long userId = tokenService.parseUserId(token);
				// 자격은 이미 서명으로 증명됐으므로 credentials는 남기지 않는다.
				// 권한 모델이 없어 authorities는 빈 목록이다(F-QJXRMD에 역할 구분이 없다).
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(userId, null, List.of()));
			} catch (JwtException | IllegalArgumentException e) {
				// 잘못된 토큰이 앞선 요청의 컨텍스트를 물려받는 일이 없도록 확실히 비운다.
				SecurityContextHolder.clearContext();
			}
		}

		filterChain.doFilter(request, response);
	}
}
