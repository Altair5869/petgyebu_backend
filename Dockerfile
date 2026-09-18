# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 의존성 해석 결과를 레이어 캐시에 남기기 위해 빌드 스크립트를 먼저 복사한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src src
# 테스트는 CI에서 별도로 돌린다. 이미지 빌드에서는 bootJar만 만든다.
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime stage ----
# h2는 build.gradle에서 developmentOnly로 선언돼 bootJar에 포함되지 않는다.
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
# chown은 COPY에서 처리한다. RUN chown -R로 하면 OverlayFS가 jar 전체를 새 레이어에 복제해
# 이미지가 jar 크기만큼 더 커진다.
COPY --from=build --chown=app:app /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
USER app

# 기본 타임존을 KST로 고정한다. eclipse-temurin 이미지와 Cloud Run은 기본이 UTC라,
# 이걸 두지 않으면 LocalDate.now()나 zone을 빠뜨린 @Scheduled가 UTC로 동작한다.
# 코드는 AppZone.KST로 존을 명시하는 것이 원칙이고 이 설정은 실수했을 때의 보험이다.
ENV TZ=Asia/Seoul

# Cloud Run은 PORT 환경변수로 수신 포트를 지정한다.
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -Dserver.port=${PORT} -jar /app/app.jar"]
