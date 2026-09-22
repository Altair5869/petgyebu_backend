package com.petgyebu.telo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spring Batch 메타 테이블 마이그레이션이 프레임워크가 제공하는 원본 SQL과 <b>바이트 단위로
 * 같은지</b> 본다.
 *
 * <p>다른 스키마 Task와 달리 T-040의 스키마는 우리 설계가 아니다. 그래서 "제약이 의도대로
 * 동작하는가"를 물을 게 아니라 "원본과 같은가"를 물어야 한다. 손으로 한 글자만 잘못 옮겨도
 * {@code CREATE TABLE}은 성공하고 배치를 돌릴 때가 되어서야 터진다.
 *
 * <p>비교 기준은 <b>실행 시점의 클래스패스</b>에 있는 원본이다. sha256을 이 테스트에 박아두지
 * 않는 것은 의도적이다. Spring Batch 버전을 올리면 원본이 바뀌는 것이 정상이고, 그때
 * "업스트림이 바뀌었는데 우리 마이그레이션은 그대로다"가 드러나야 한다.
 *
 * <p>Docker도 Spring 컨텍스트도 필요 없다. 파일 두 개를 읽어 비교할 뿐이다.
 */
@DisplayName("Spring Batch 메타 스키마가 업스트림 원본과 일치한다")
class BatchSchemaSourceTest {

	/** 우리 마이그레이션. 클래스패스 루트 기준 경로다. */
	private static final String MIGRATION_RESOURCE =
			"db/migration/V202609221408__create_spring_batch_metadata.sql";

	/** spring-batch-core jar 안의 원본. */
	private static final String UPSTREAM_RESOURCE =
			"org/springframework/batch/core/schema-postgresql.sql";

	/** 이 줄 다음부터 파일 끝까지가 원본 복사 구간이다. 마커 위는 우리가 붙인 주석이다. */
	private static final String MARKER = "-- >>> BEGIN spring-batch-core schema-postgresql.sql >>>\n";

	@Test
	@DisplayName("마커 아래 구간이 jar 원본과 바이트 단위로 같다")
	void migrationBodyMatchesUpstreamByteForByte() throws IOException {
		// ISO-8859-1은 바이트와 문자가 1:1로 대응한다. 바이트 비교의 엄밀함을 유지하면서
		// 실패했을 때 assertj가 읽을 수 있는 diff를 보여준다.
		String migration = new String(read(MIGRATION_RESOURCE), StandardCharsets.ISO_8859_1);
		String upstream = new String(read(UPSTREAM_RESOURCE), StandardCharsets.ISO_8859_1);

		int markerEnd = migration.indexOf(MARKER);
		assertThat(markerEnd)
				.as("마이그레이션에서 원본 시작 마커를 찾지 못했다. 마커를 지웠거나 바꿨다면 "
						+ "이 테스트의 MARKER 상수도 함께 맞춰야 한다")
				.isNotNegative();

		String copiedBody = migration.substring(markerEnd + MARKER.length());

		assertThat(copiedBody)
				.as("마이그레이션의 원본 복사 구간이 클래스패스의 %s와 다르다. "
						+ "사람이 SQL을 손댔거나, Spring Batch 버전이 올라가 업스트림 스키마가 "
						+ "바뀐 것이다. 후자라면 새 jar에서 다시 추출해 마커 아래를 통째로 교체하고 "
						+ "헤더의 sha256도 갱신하라", UPSTREAM_RESOURCE)
				.isEqualTo(upstream);
	}

	private static byte[] read(String resource) throws IOException {
		try (InputStream in = BatchSchemaSourceTest.class.getClassLoader().getResourceAsStream(resource)) {
			assertThat(in).as("클래스패스에서 %s를 찾지 못했다", resource).isNotNull();
			return in.readAllBytes();
		}
	}
}
