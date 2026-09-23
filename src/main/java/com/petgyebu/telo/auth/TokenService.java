package com.petgyebu.telo.auth;

import com.petgyebu.telo.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 액세스 토큰 발급·검증(T-003a).
 *
 * <p>리프레시 토큰은 여기 없다. 회전·폐기 토큰 재사용 감지는 폐기 상태 저장소로 Redis를 쓰기로
 * 했고(F-QJXRMD exceptions, {@code docs/09-db-design.md} 2.2절) 아직 미프로비저닝이라
 * T-003b로 미뤘다. 이 클래스는 Redis 없이 성립하는 범위만 담는다.
 *
 * <p>발급 진입점(HTTP)은 아직 없다. 카카오 인가 코드 교환(T-002)이 붙을 때 그 컨트롤러가
 * {@link #issueAccessToken(User)}를 호출한다. 그때까지 이 서비스는 테스트에서만 불린다.
 */
@Service
public class TokenService {

	/** F-QJXRMD rules: "액세스 토큰 만료는 30분". 설정으로 빼지 않는다 — 명세가 정한 값이다. */
	static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);

	private static final String BEARER_PREFIX = "Bearer ";

	private final SecretKey signingKey;
	private final Clock clock;

	/**
	 * @param secret HS256 서명 키. 코드에 두지 않고 설정에서만 주입한다. 256비트(UTF-8로 32바이트)
	 *               미만이면 {@link Keys#hmacShaKeyFor(byte[])}가 기동 시점에 예외를 던진다 —
	 *               약한 키로 조용히 돌기보다 부팅이 실패하는 쪽이 낫다.
	 * @param clock  KST 기준 시계({@code AppZone.clock()}). 발급 시각과 만료 판정이 같은 시계를
	 *               보게 하려고 파서에도 이 시계를 넘긴다. 테스트는 고정 시계를 끼워 만료를 실제로
	 *               지나가게 만든다.
	 */
	public TokenService(@Value("${telo.auth.access-token-secret}") String secret, Clock clock) {
		this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.clock = clock;
	}

	/**
	 * 액세스 토큰을 발급한다. 클레임은 {@code sub} = {@code users.id}뿐이다.
	 *
	 * <p>닉네임·권한 같은 것을 토큰에 싣지 않는 이유: 토큰은 30분간 무효화할 수 없어서 안에 든
	 * 값은 그동안 낡은 채로 돌아다닌다. 식별자만 담고 나머지는 매 요청 DB에서 읽는다.
	 */
	public String issueAccessToken(User user) {
		Long userId = Objects.requireNonNull(user.getId(), "저장되지 않은 User로는 토큰을 발급할 수 없다");
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(ACCESS_TOKEN_TTL)))
				.signWith(signingKey, Jwts.SIG.HS256)
				.compact();
	}

	/**
	 * 토큰을 검증하고 {@code sub}를 {@code users.id}로 읽는다.
	 *
	 * <p>서명 불일치·만료·형식 오류는 모두 {@link JwtException}으로 나간다. 호출자(필터)가
	 * 사유를 구분하지 않는 이유는 응답이 어느 쪽이든 401이고, 사유를 밖으로 알리면 공격자에게
	 * 힌트가 되기 때문이다.
	 *
	 * @throws JwtException 서명이 맞지 않거나 만료됐거나 형식이 깨졌을 때
	 */
	public Long parseUserId(String token) {
		Jws<Claims> jws = Jwts.parser()
				.verifyWith(signingKey)
				// 파서의 기본 시계는 System.currentTimeMillis()라 주입받은 시계를 무시한다.
				// 넘기지 않으면 고정 시계를 앞당겨도 만료가 재현되지 않는다.
				.clock(() -> Date.from(clock.instant()))
				.build()
				.parseSignedClaims(token);

		// 파서는 헤더의 alg를 그대로 믿는다. 같은 비밀키로 HS384·HS512로 서명한 토큰도
		// 통과한다. 발급은 HS256만 하므로 검증도 HS256으로 좁힌다.
		//
		// 지금 키가 32바이트라 HS384 서명은 길이 제약에 우연히 걸리지만, 권장대로 키를
		// 64바이트로 늘리는 순간 그 우연이 사라진다. 키를 모르면 위조는 어차피 불가능하니
		// 심층방어다 — 우연에 기대는 방어선을 의도한 방어선으로 바꾼다.
		String algorithm = jws.getHeader().getAlgorithm();
		if (!Jwts.SIG.HS256.getId().equals(algorithm)) {
			throw new MalformedJwtException("HS256으로 서명되지 않은 토큰이다: " + algorithm);
		}

		String subject = jws.getPayload().getSubject();
		try {
			return Long.valueOf(subject);
		} catch (NumberFormatException e) {
			// 서명은 맞는데 sub가 숫자가 아니다 = 우리가 발급한 형식이 아니다. 인증 실패로 다룬다.
			throw new MalformedJwtException("sub가 users.id 형식이 아니다", e);
		}
	}

	/**
	 * {@code Authorization: Bearer <token>}에서 토큰만 떼어낸다. 헤더가 없거나 스킴이 다르면
	 * {@code null}을 돌려준다 — 토큰을 들고 오지 않은 요청은 오류가 아니라 그냥 익명 요청이다.
	 */
	static String extractBearerToken(String authorizationHeader) {
		if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
			return null;
		}
		return authorizationHeader.substring(BEARER_PREFIX.length());
	}
}
