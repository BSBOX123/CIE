# ── 빌드 ──────────────────────────────────────────────────────────
# Spring Boot 3.4 는 JDK 21 을 쓴다. 로컬 기본 JDK 가 무엇이든 여기서는
# 21 로 고정되므로 "내 컴퓨터에선 되는데" 문제가 생기지 않는다.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# 의존성 레이어를 먼저 굳혀 소스만 바뀔 때 재다운로드하지 않게 한다.
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ── 실행 ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# root 로 돌리지 않는다.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/build/libs/*.jar app.jar
RUN chown app:app app.jar
USER app

# EC2 프리티어(1GB)에서 JVM 이 메모리를 과하게 잡지 않도록 컨테이너 인식 옵션.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
