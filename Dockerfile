# syntax=docker/dockerfile:1.6
# ---------------------------------------------------------------
# ContENIDO API — 멀티스테이지 Docker 빌드 (PR49)
#
# Stage 1 (builder): Gradle + JDK 21 로 fat jar 생성.
# Stage 2 (runtime): Eclipse Temurin JRE 21 (alpine) — non-root, slim.
#
# 빌드:
#   docker build -t contenido-api:dev .
# 실행 (prod):
#   docker run -d --name contenido -p 8080:8080 \
#     -e DB_URL="jdbc:mysql://db:3306/contenido?..." \
#     -e DB_USERNAME=... -e DB_PASSWORD=... \
#     -e REDIS_HOST=... -e REDIS_PORT=6379 -e REDIS_PASSWORD=... \
#     -e ELASTICSEARCH_URI=http://es:9200 \
#     -e JWT_SECRET=... \
#     contenido-api:dev
#
# SPRING_PROFILES_ACTIVE 는 prod 기본. 다른 프로파일은 docker run -e 로 override.
# ---------------------------------------------------------------

# ===== Stage 1: Build =====
FROM gradle:8.8-jdk21-alpine AS builder
WORKDIR /workspace

# 의존성 캐시 분리 — gradle 파일만 먼저 복사해서 다운로드 레이어 캐싱
COPY --chown=gradle:gradle settings.gradle.kts build.gradle.kts ./
COPY --chown=gradle:gradle gradle ./gradle
RUN gradle dependencies --no-daemon || true

# 본 소스 복사 후 bootJar (테스트는 CI 에서 별도 — 이미지 빌드 시 skip)
COPY --chown=gradle:gradle src ./src
RUN gradle bootJar --no-daemon -x test

# ===== Stage 2: Runtime =====
FROM eclipse-temurin:21-jre-alpine

# non-root user
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

COPY --from=builder --chown=spring:spring /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar

USER spring:spring

# 기본 운영 프로파일. 로컬 테스트는 -e SPRING_PROFILES_ACTIVE=local 로 override.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080

# Actuator readiness probe 가 200 이면 컨테이너 healthy.
# kubernetes 등에서는 별도 livenessProbe/readinessProbe 설정 권장.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
