package com.petgyebu.telo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 액세스 토큰 종단 검증(T-003a).
 *
 * <p><b>모의 인증({@code @WithMockUser})을 쓰지 않는다.</b> 모의 인증은 시큐리티 컨텍스트를
 * 바로 채워버려서 필터·헤더 파싱·서명 검증·만료 판정을 전부 건너뛴다. 그러면 필터를 등록하지
 * 않아도 테스트가 통과한다. 여기서는 {@link TokenService}로 진짜 토큰을 발급해
 * {@code Authorization} 헤더에 실어 보낸다.
 *
 * <p>만료는 고정 시계를 앞으로 옮겨 재현한다. 30분을 기다릴 수 없고, 토큰 문자열을 손으로
 * 조작하면 서명이 깨져 "만료"가 아니라 "서명 오류"를 보게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisplayName("액세스 토큰 발급·검증 종단")
class AccessTokenAuthenticationTest {

	/** KST 2026-09-23 10:00. 테스트 JVM은 UTC로 도는데(build.gradle) 기준 시각은 KST다. */
	private static final Instant ISSUED_AT = Instant.parse("2026-09-23T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MutableClock clock;

	private Long userId;

	@BeforeEach
	void setUp() {
		clock.set(ISSUED_AT);
		User user = userRepository.save(new User(
				AuthProvider.KAKAO, "kakao-" + System.nanoTime(), null,
				OffsetDateTime.ofInstant(ISSUED_AT, AppZone.KST)));
		this.userId = user.getId();
	}

	@Test
	@DisplayName("유효한 토큰이면 200이고, 컨트롤러가 받은 userId가 발급 시 sub와 같다")
	void validTokenAuthenticates() throws Exception {
		String token = tokenService.issueAccessToken(userRepository.findById(userId).orElseThrow());

		// 발급한 토큰의 sub 자체가 users.id인지 먼저 확인한다. 아래 응답 단언만으로는
		// 컨트롤러가 다른 경로로 id를 얻어왔을 가능성을 배제하지 못한다.
		assertThat(tokenService.parseUserId(token)).isEqualTo(userId);

		mockMvc.perform(get("/__test__/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(userId));
	}

	@Test
	@DisplayName("토큰이 없으면 401")
	void missingTokenIsRejected() throws Exception {
		mockMvc.perform(get("/__test__/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("서명이 다른 토큰은 401 — 본문이 그럴듯해도 우리 키로 서명되지 않았다")
	void wrongSignatureIsRejected() throws Exception {
		String forged = Jwts.builder()
				.subject(String.valueOf(userId))
				.issuedAt(Date.from(ISSUED_AT))
				.expiration(Date.from(ISSUED_AT.plus(Duration.ofMinutes(30))))
				.signWith(Keys.hmacShaKeyFor(
						"attacker-key-that-is-long-enough-32bytes".getBytes(StandardCharsets.UTF_8)),
						Jwts.SIG.HS256)
				.compact();

		mockMvc.perform(get("/__test__/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("만료 30분이 지나면 401 — 29분 59초에는 아직 통과한다")
	void expiredTokenIsRejected() throws Exception {
		String token = tokenService.issueAccessToken(userRepository.findById(userId).orElseThrow());

		// 양방향으로 본다. 거부만 확인하면 "항상 401"인 구현도 통과한다.
		clock.set(ISSUED_AT.plus(Duration.ofMinutes(30)).minusSeconds(1));
		mockMvc.perform(get("/__test__/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		clock.set(ISSUED_AT.plus(Duration.ofMinutes(30)).plusSeconds(1));
		mockMvc.perform(get("/__test__/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("/actuator/health는 토큰 없이도 200 — 기존 동작이 필터 추가로 깨지지 않는다")
	void healthStaysOpen() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk());
	}

	/**
	 * 보호된 테스트 전용 엔드포인트와 고정 시계. 프로덕션 코드에 두지 않는다 — T-003a에는
	 * 아직 컨트롤러가 없고, 검증을 위해 실제 API 표면을 늘리지 않는다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class TestEndpointConfig {

		/**
		 * 빈 이름을 {@code clock}으로 두면 {@code TimeConfig}의 빈과 충돌해 기동이 실패한다
		 * (Boot는 빈 정의 덮어쓰기를 기본으로 막는다). 다른 이름에 {@code @Primary}로 이긴다.
		 */
		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(ISSUED_AT);
		}

		@Bean
		TestMeController testMeController() {
			return new TestMeController();
		}
	}

	@RestController
	static class TestMeController {

		/** 경로에 userId를 받지 않는다. 인증 주체는 토큰에서만 온다. */
		@GetMapping("/__test__/me")
		Map<String, Long> me(@AuthenticationPrincipal Long userId) {
			return Map.of("userId", userId);
		}
	}

	/** 앞뒤로 옮길 수 있는 시계. 만료 판정을 실제로 지나가게 하려고 쓴다. */
	static class MutableClock extends Clock {

		private volatile Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return AppZone.KST;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("테스트 시계는 존을 바꾸지 않는다");
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
