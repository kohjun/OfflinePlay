# WOYA 개발 환경 셋업

CONTENIDO MVP (Spring Boot Kotlin 백엔드 + Vite/React 모바일 프론트) 로컬 실행 가이드.

## 요구사항

| 항목 | 버전 |
|---|---|
| JDK | 21 |
| Node.js | 18+ (Vite 5 권장) |
| MySQL | 8.0+ |
| Redis | 6+ |
| Elasticsearch | 8.x (선택 — 검색/탐색 끄려면 후술 참고) |

Windows/macOS 어디서나 동작. Windows 는 PowerShell + `gradlew.bat`, macOS/Linux 는 `gradlew` 사용.

## 첫 셋업

### 1. 의존 서비스 기동

`docker compose` 가 있다면 한 번에 띄우는 게 가장 빠릅니다. 직접 띄울 경우:

```
# MySQL
docker run -d --name woya-mysql \
  -e MYSQL_ROOT_PASSWORD=password \
  -e MYSQL_DATABASE=contenido \
  -p 3306:3306 mysql:8.0

# Redis
docker run -d --name woya-redis -p 6379:6379 redis:7

# Elasticsearch (선택 — 탐색 기능을 끄려면 생략 가능)
docker run -d --name woya-es \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  -p 9200:9200 elasticsearch:8.13.4
```

### 2. 로컬 프로파일 설정

`src/main/resources/application-local.yml` 은 **gitignore** 되어 있어서 직접 만들거나 팀원이 공유한 값을 받아 두어야 합니다. 기본 템플릿:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/contenido?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:password}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: create   # 로컬 개발 — 시작할 때마다 스키마 재생성
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true
    show-sql: true

  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD:redis1234}

  elasticsearch:
    uris: http://localhost:9200

cloud:
  aws:
    s3:
      bucket: local-woya-bucket
      region: ap-northeast-2
      base-url: https://local-woya-bucket.s3.ap-northeast-2.amazonaws.com
    credentials:
      access-key: local-access-key
      secret-key: local-secret-key

logging:
  level:
    com.contenido: DEBUG
    org.springframework.security: DEBUG
    org.hibernate.SQL: DEBUG
```

JWT secret, S3 자격증명 같은 값은 운영 배포 시 환경변수 (`JWT_SECRET`, `AWS_*`) 로 덮어씁니다.

## 백엔드

```
# 실행 (기본 프로파일 = local)
./gradlew.bat bootRun

# 테스트
./gradlew.bat test

# JAR 빌드
./gradlew.bat build
```

- 서버 포트: `8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- 첫 실행 시 `ddl-auto: create` 로 테이블 자동 생성. 운영은 `update` 또는 Flyway 도입 예정.

### MySQL 필수?

**예 — 로컬 실행은 MySQL 이 필요**. JPA 가 MySQLDialect 로 동작하고, 통합 테스트는 H2 가 아닌 인메모리/임베디드 DB 없이 mock 으로 우회합니다.

CI/테스트만 돌리는 거라면 MySQL 없이 `./gradlew.bat test` 만으로 충분 (모든 서비스 테스트는 MockK, 통합 테스트는 `application-test.yml` 가 별도 설정).

### Elasticsearch 없이 돌리고 싶을 때

ES 인덱스 동기화는 `@TransactionalEventListener` 로 best-effort 동작. ES 가 꺼져 있어도 본 흐름은 깨지지 않지만, `/api/v1/explore` 등 검색 엔드포인트는 빈 결과를 돌려줍니다.

## 프론트엔드

```
cd frontend
npm install          # 첫 실행 1회
npm run dev          # http://localhost:5173
npm run typecheck    # tsc -b --noEmit
npm run build        # 프로덕션 빌드 (tsc + vite build)
npm run preview      # 빌드 결과 미리보기
```

- API base URL 은 `frontend/src/api/client.ts` 에서 관리. 기본값은 `http://localhost:8080/api/v1`.
- SSE 알림 스트림은 인증된 사용자에 한해 App 마운트 시점에 자동 연결 (`useNotificationStream`).

## 테스트 명령 요약

| 목적 | 명령 |
|---|---|
| 백엔드 전체 테스트 | `./gradlew.bat test` |
| 백엔드 특정 클래스 | `./gradlew.bat test --tests "com.contenido.domain.event.service.EventServiceTest"` |
| 백엔드 strict rerun (캐시 무시) | `./gradlew.bat test --rerun-tasks` |
| 프론트 타입체크 | `cd frontend; npm run typecheck` |
| 프론트 프로덕션 빌드 | `cd frontend; npm run build` |

## .gitignore 가 다루는 산출물

다음은 모두 무시되며 절대 커밋 대상 아님:

- `bin/` (Eclipse 빌드 미러), `build/` (Gradle 빌드 산출물)
- `.appdata/`, `.gradle-codex/`, `.localappdata/` (로컬 도구 캐시)
- `.claude/settings.local.json` (Claude Code 머신별 권한 캐시)
- `frontend/*.tsbuildinfo` (TS incremental 캐시), `frontend/node_modules`, `frontend/dist`
- `src/main/resources/application-local.yml` (DB 자격증명 포함)
- `*.log`, `logs/`

## 문제해결

- **`Connection refused` (MySQL)** — MySQL 컨테이너 / 서비스 띄웠는지 확인. 포트 3306 점유 여부.
- **`Connection refused` (Redis/ES)** — 마찬가지. ES 가 안 떠 있어도 본 흐름은 동작하나 SearchSyncService 가 경고 로그를 남깁니다.
- **테스트가 `429 Too Many Requests` 로 실패** — 통합 테스트는 `application-test.yml` 에서 rate-limit 이 꺼져 있어야 함 (이미 설정됨).
- **`Unsatisfied dependency: S3Service`** — `application-test.yml` 에 가짜 AWS 자격증명이 있어야 함 (이미 설정됨).
