package com.petgyebu.telo.common.time;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 서비스의 기준 타임존.
 *
 * <p>{@code docs/09-db-design.md} 0장이 "모든 시각 계산의 기준 타임존은 KST"로 정했다. 이 클래스가
 * 그 단일 출처다. 코드 어디서도 {@code ZoneId.of("Asia/Seoul")}을 다시 쓰지 말고 여기를 참조한다.
 *
 * <h2>날짜로 자르는 계산에는 반드시 존을 명시한다</h2>
 *
 * KST는 UTC+9다. 한국 시각 00:00~09:00 사이는 UTC로는 아직 전날이다. 저장은 문제가 없다 —
 * 모든 시각 컬럼이 {@code TIMESTAMPTZ}이고 엔티티는 {@code OffsetDateTime}이라 절대 시각이
 * 그대로 보존된다. 문제는 그 시각을 <b>날짜로 환산</b>할 때 생긴다.
 *
 * <pre>
 * 거래 절대 시각        : 2026-09-30T16:00:00Z
 * 한국 사용자가 본 시각 : 2026-10-01 01:00   (10월 1일 새벽)
 *
 * UTC 기준 날짜         : 2026-09-30   → 9월 예산에 집계된다
 * KST 기준 날짜         : 2026-10-01   → 10월이 맞다
 * </pre>
 *
 * 사용자는 10월 1일 새벽에 쓴 돈인데 서버가 UTC로 자르면 9월 지출로 센다. 매달 1일 00:00~09:00에
 * 9시간짜리 구멍이 생긴다.
 *
 * <h2>지켜야 할 것</h2>
 *
 * <ul>
 *   <li>{@code LocalDate.now()}, {@code LocalDateTime.now()}처럼 <b>존 없는 호출을 쓰지 않는다.</b>
 *       {@code LocalDate.now(AppZone.KST)}처럼 항상 존을 넘긴다. 이러면 서버 기본 타임존이
 *       무엇이든 결과가 같다.</li>
 *   <li>{@code @Scheduled}에는 {@code zone = "Asia/Seoul"}을 명시한다. 빠뜨리면 JVM 기본 존으로
 *       동작해 s9 배치가 9시간 늦게 실행된다.</li>
 * </ul>
 *
 * 운영 컨테이너는 {@code TZ=Asia/Seoul}로 기본 존도 KST에 맞춰 두지만, 그건 실수했을 때 피해를
 * 줄이는 보험일 뿐이다. 코드가 기본 존에 의존하지 않는 것이 먼저다. 테스트는 일부러 UTC로 돌려
 * 기본 존에 의존하는 코드가 드러나게 한다({@code build.gradle}의 test 블록 참고).
 */
public final class AppZone {

	/** 서비스 기준 타임존. 모든 날짜 환산·기간 경계·스케줄 판단에 쓴다. */
	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** {@code @Scheduled(zone = ...)}에 넣을 문자열. {@link #KST}와 같은 값이다. */
	public static final String KST_ID = "Asia/Seoul";

	/**
	 * KST 기준 시계. 시각을 다루는 컴포넌트는 이 빈이나 {@link #KST}를 주입받아 쓰고,
	 * 테스트에서는 고정 시계로 바꿔 끼운다.
	 */
	public static Clock clock() {
		return Clock.system(KST);
	}

	private AppZone() {
	}
}
