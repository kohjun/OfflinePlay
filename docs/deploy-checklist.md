# ContENIDO 운영 배포 체크리스트

> PR49 — Actuator / Dockerfile / Flyway baseline / GitHub Actions 가 들어오면서 정리한 운영
> 배포 1.0 체크리스트. 실 배포가 진행되면 incident 단위로 업데이트.

## 1. 필수 환경 변수

`application-prod.yml` 이 직접 참조하는 값. 누락 시 부팅 실패하거나 (`PaymentHardeningCheck`),
조용히 데이터를 잃을 수 있으므로 **secrets manager / k8s Secret 으로 주입한다**. 절대 git 에
올리지 않는다.

| 카테고리 | 변수 | 설명 |
| --- | --- | --- |
| DB | `DB_URL` | `jdbc:mysql://host:3306/contenido?useSSL=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8` |
| DB | `DB_USERNAME` | 운영 DB 사용자 — DDL 권한 있는 마이그레이션 전용 계정 권장 |
| DB | `DB_PASSWORD` | |
| Redis | `REDIS_HOST` | 캐시·인기 검색어·SSE 브로드캐스트에 사용 |
| Redis | `REDIS_PORT` | |
| Redis | `REDIS_PASSWORD` | |
| Elasticsearch | `ELASTICSEARCH_URI` | `http://host:9200` |
| JWT | `JWT_SECRET` | Base64 인코딩된 256-bit 이상 키 |
| S3 | `AWS_S3_BUCKET` | 업로드 버킷 |
| S3 | `AWS_REGION` | 예: `ap-northeast-2` |
| S3 | `AWS_S3_BASE_URL` | 공개 객체 URL prefix |
| S3 | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | IAM 키 (또는 IAM Role) |
| Payment | `TOSS_PAYMENTS_ENABLED` | `true` 로 두고 secret-key 누락 시 부팅 차단 |
| Payment | `TOSS_SECRET_KEY` | `live_sk_*` |
| Payment | `TOSS_CLIENT_KEY` | 프론트 SDK 키 |
| Payment | `TOSS_API_BASE_URL` | 기본값 `https://api.tosspayments.com` |
| Payment | `TOSS_WEBHOOK_SIGNATURE_REQUIRED` | 운영 기본 `true` |

`docs/payment-refund-policy.md §11` 도 함께 확인 — 결제 hardening 게이트가 부팅 시 살아 있는지.

## 2. 헬스체크 / Actuator

PR49 에서 노출한 엔드포인트 (모두 `permitAll`):

| URL | 용도 | prod 동작 |
| --- | --- | --- |
| `GET /actuator/health` | 종합 health | `show-details: never` — `{"status":"UP"}` 만 |
| `GET /actuator/health/liveness` | k8s livenessProbe | 200 = 살아 있음 |
| `GET /actuator/health/readiness` | k8s readinessProbe | 200 = 트래픽 받을 준비 됨 |
| `GET /actuator/info` | 빌드/앱 메타 | `info.app.*` 정도만 |

**노출하지 않은 엔드포인트**: `metrics`, `prometheus`, `env`, `configprops`, `loggers`, `threaddump`,
`heapdump`. 후속 PR 에서 인증 게이트(예: hasRole('ADMIN') 또는 internal-only path) 와 함께 노출.

쿠버네티스 manifest 예 (`livenessProbe` / `readinessProbe`):

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 40
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 20
  periodSeconds: 5
```

## 3. Docker

루트 `Dockerfile` 은 멀티스테이지:
1. `gradle:8.8-jdk21-alpine` 으로 `bootJar` 빌드 (테스트 skip).
2. `eclipse-temurin:21-jre-alpine` non-root user (`spring`) 로 실행.

```bash
docker build -t contenido-api:$(git rev-parse --short HEAD) .
docker run -d --name contenido -p 8080:8080 \
  --env-file .env.prod \
  contenido-api:$(git rev-parse --short HEAD)
```

`.env.prod` 는 §1 변수만 담고 절대 commit 금지.

이미지 자체 HEALTHCHECK 는 readiness probe 를 30s 주기로 호출. k8s 환경이면 위 §2 probes 가
권위 있으니 이미지 HEALTHCHECK 는 redundant — 그대로 두어도 무해.

## 4. Flyway 마이그레이션

- 베이스라인: `src/main/resources/db/migration/V1__init.sql` — PR49 까지의 JPA 스키마.
- prod 만 활성화 (`application-prod.yml`): `spring.flyway.enabled=true`,
  `baseline-on-migrate=true`, `baseline-version=0`.
- 운영 DB 가 이미 V1 스키마를 갖고 있다면 (이전에 ddl-auto=create 로 만들어진 경우):
  `baseline-on-migrate=true` 가 V1 을 baseline 으로 잡고 V2+ 부터 적용한다 — 데이터 손실 X.
- **V1 은 한 번 배포된 후 절대 수정 금지**. 잘못된 컬럼/제약은 V2 마이그레이션으로 보정.
- local / test 는 `spring.flyway.enabled=false` 로 비활성화. local 은 ddl-auto=create,
  test 는 H2 create-drop 유지.

### 첫 운영 배포 검증

1. staging 또는 동일 사양 DB 에서 `spring.jpa.hibernate.ddl-auto=validate` 로 부팅.
2. 부팅 실패 (컬럼 타입/제약 mismatch) 시 차이를 V2 마이그레이션으로 보정.
3. 통과하면 prod 으로 승격.

### PR48 schema 승격

V1 에 `reports(reporter_id, target_type, target_id) UNIQUE` 가 들어가 있다 — PR48 의
service-level 중복 신고 가드를 schema-level UNIQUE 로 승격한 것. 운영 데이터에 이미 중복
신고가 있다면 V1 적용 전 manual cleanup 필요:

```sql
SELECT reporter_id, target_type, target_id, COUNT(*)
FROM reports
GROUP BY reporter_id, target_type, target_id
HAVING COUNT(*) > 1;
```

## 5. CI (GitHub Actions)

`.github/workflows/ci.yml` — main 푸시/PR 마다 2개 job:

- **backend**: `compileKotlin` + `compileTestKotlin` + service 단위 테스트 (`com.contenido.domain.*.service.*`).
- **frontend**: `npm ci` + `npm run build` (tsc -b + vite).

### 알려진 제약 — 전체 backend test hang

전체 `./gradlew test` 는 PR42 이래로 `@SpringBootTest` 부팅 단계에서 hang 되는 인프라 이슈가
있다. 원인 분리/해결은 후속 PR. 현재 CI 는:
- service 단위 테스트만 명시 실행 — mockk 기반이라 안정.
- 통합 테스트가 hang 으로 누락된 영역은 수동 QA 체크리스트 (`docs/manual-qa-checklist.md`) 와
  운영 readiness probe 가 보강한다.

### 게이트 통과 기준

- backend job: green.
- frontend job: green.
- 둘 다 통과해야 main merge 가능 (GitHub repo settings 의 branch protection 에서 별도 설정 필요).

## 6. 배포 전 마지막 체크

- [ ] §1 환경 변수 모두 secrets 에 들어가 있는가 (특히 `JWT_SECRET`, `TOSS_SECRET_KEY`,
      `AWS_SECRET_ACCESS_KEY`).
- [ ] DB 의 마이그레이션 권한 / 일반 권한 계정 분리.
- [ ] 첫 배포는 staging 에서 `ddl-auto=validate` + `flyway.enabled=true` 로 검증 끝났는가.
- [ ] readiness/liveness probe 가 k8s manifest 에 들어 있는가.
- [ ] `TOSS_WEBHOOK_SIGNATURE_REQUIRED=true` (PaymentHardeningCheck 가 부팅 차단).
- [ ] PR48 schema 승격을 위해 `reports` 중복 행 cleanup 완료.
- [ ] 로그 수집 (CloudWatch / Loki 등) 이 컨테이너 stdout 을 받고 있는가.

## 7. 후속 과제

- 신고 누적 자동 비공개 정책 (별도 PR).
- 전체 `./gradlew test` hang 원인 분리 — 인프라 PR (Redis / Elasticsearch / @SpringBootTest
  부팅 sequence 점검).
- Actuator metrics/prometheus 노출 — 인증 게이트 (basic auth 또는 internal-only path) 와 함께.
- Flyway V2 — 운영 첫 검증 후 mismatch 보정.
