package com.petgyebu.telo.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기준 타임존 규칙을 고정하는 테스트.
 *
 * <p>이 테스트는 JVM 기본 존이 UTC인 상태에서 돈다({@code build.gradle}의 test 블록에서
 * {@code user.timezone=UTC}를 준다). 운영 컨테이너는 {@code TZ=Asia/Seoul}이므로 기본 존이
 * 서로 다르며, 그게 의도다. 존을 명시하지 않은 계산은 두 환경에서 다른 답을 내므로 여기서 깨진다.
 */
@DisplayName("기준 타임존 KST 규칙")
class AppZoneTest {

	/** 한국 시각으로 2026년 10월 1일 01:00. UTC로는 아직 9월 30일이다. */
	private static final Instant EARLY_OCTOBER_KST = Instant.parse("2026-09-30T16:00:00Z");

	@Test
	@DisplayName("테스트는 UTC로 돈다 — 존을 빠뜨린 코드가 드러나야 하기 때문이다")
	void testsRunInUtc() {
		assertThat(ZoneId.systemDefault())
				.as("build.gradle의 test 블록이 user.timezone=UTC를 주도록 돼 있다. "
						+ "이 단언이 깨졌다면 그 설정이 사라진 것이다")
				.isEqualTo(ZoneId.of("UTC"));
	}

	@Test
	@DisplayName("월 경계 근처 시각은 기본 존과 KST에서 날짜가 갈린다")
	void dateDivergesNearMonthBoundary() {
		LocalDate byDefaultZone = LocalDate.ofInstant(EARLY_OCTOBER_KST, ZoneId.systemDefault());
		LocalDate byKst = LocalDate.ofInstant(EARLY_OCTOBER_KST, AppZone.KST);

		// 같은 절대 시각인데 날짜가 다르다. 이것이 존을 명시해야 하는 이유다.
		assertThat(byDefaultZone).isEqualTo(LocalDate.of(2026, 9, 30));
		assertThat(byKst).isEqualTo(LocalDate.of(2026, 10, 1));

		assertThat(byDefaultZone)
				.as("기본 존으로 자르면 10월 1일 새벽 거래가 9월 지출로 집계된다")
				.isNotEqualTo(byKst);
	}

	@Test
	@DisplayName("KST 기준 날짜는 한국 사용자가 보는 날짜와 같다")
	void kstDateMatchesWhatUserSees() {
		LocalDate byKst = LocalDate.ofInstant(EARLY_OCTOBER_KST, AppZone.KST);

		assertThat(byKst)
				.as("한국 사용자는 10월 1일 새벽 1시에 결제했다. 10월 예산에 잡혀야 한다")
				.isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@Test
	@DisplayName("AppZone.KST_ID는 @Scheduled(zone = ...)에 그대로 쓸 수 있는 값이다")
	void scheduledZoneIdMatches() {
		assertThat(ZoneId.of(AppZone.KST_ID)).isEqualTo(AppZone.KST);
	}

	@Test
	@DisplayName("AppZone.clock()은 KST 기준이다")
	void clockUsesKst() {
		assertThat(AppZone.clock().getZone()).isEqualTo(AppZone.KST);
	}
}
