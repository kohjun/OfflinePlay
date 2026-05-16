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

- **backend**: `compileKotlin` + `compileTestKotlin` + 전체 `./gradlew test` (278 tests).
- **frontend**: `npm ci` + `npm run build` (tsc -b + vite).

### Spring Boot test 부팅 안정화 (PR50)

PR42 이래 전체 `./gradlew test` 가 `@SpringBootTest` 부팅 단계에서 매달리던 이슈가 해결됐다.
원인은 3개 축이 겹쳐 있었다:

1. **`PaymentWebhookControllerTest` 가 `RedisTemplate` / `ElasticsearchOperations` mock 누락.**
   `application-test.yml` 이 RedisAutoConfiguration / ElasticsearchAutoConfiguration 을
   exclude 하므로 명시적으로 mock 을 제공해야 컨텍스트가 떴다. 다른 통합 테스트
   (Auth/Channel/Permission) 는 이미 두 mock 을 갖고 있어 통과해 왔다. PR50 이 해당
   테스트에 `@MockkBean(relaxed = true) RedisTemplate`, `ElasticsearchOperations` 두 줄을
   추가했다.

2. **`EventStatusScheduler` 의 `@Scheduled` 가 테스트 컨텍스트에서도 등록되어 non-daemon
   `ThreadPoolTaskScheduler` thread 를 잡았다.** 다중 `@SpringBootTest` 클래스가 누적되면
   JVM 종료가 분 단위로 매달리는 원인. PR50 이 `@Profile("!test")` 로 prod/local 에서만
   등록되게 했다 — 운영 동작 변화 없음.

3. **테스트 풀의 graceful shutdown 미설정.** `application-test.yml` 의
   `spring.task.execution.shutdown.await-termination=true` (5s) +
   `spring.task.scheduling.shutdown.await-termination=true` (5s) + 풀 크기를 작게 (1~2)
   잡아 컨텍스트 close 시 thread 청소가 보장된다.

검증: `./gradlew.bat test --console=plain --no-daemon` → 53s, 278/278 green
(failures=0, errors=0, skipped=0).

### 게이트 통과 기준

- backend job: green.
- frontend job: green.
- 둘 다 통과해야 main merge 가능 (GitHub repo settings 의 branch protection 에서 별도 설정 필요).

## 6. Moderation 자동 hide 임계치 (PR60)

PR51 에서 `ReportService.AUTO_HIDE_THRESHOLDS` 상수로 박혀 있던 PENDING 신고 누적 임계치를
PR60 에서 DB 의 `moderation_threshold_settings` 테이블로 옮겼다. ADMIN 이 운영 지표(PR57) 를
보고 운영 중 직접 조정 가능.

| target_type | default | 이유 |
| --- | --- | --- |
| REVIEW  | 3 | 노출 사이클 짧고 영향 범위 좁음 — 빠른 숨김 안전 |
| COMMENT | 3 | 동일 |
| POST    | 5 | 운영자 콘텐츠 — 오탐 비용 큼 |
| EVENT   | 5 | 동일 |
| CHANNEL | 7 | 모든 소속 콘텐츠가 함께 가려짐 — 가장 비용 큼 |

V4 마이그레이션(`V4__add_moderation_threshold_settings.sql`) 이 위 5 row 를 seed 한다.

운영 중 조정:
- ADMIN 페이지의 "자동 숨김 임계치" 카드에서 1~100 범위로 변경.
- `PATCH /api/v1/admin/moderation/thresholds` (ADMIN-only) 직접 호출도 가능.
- 변경 즉시 **다음 신고부터** 새 임계치 적용. **기존 hidden 상태는 retroactive 재계산되지 않음**
  (즉, 임계치를 낮춰도 이미 노출 중인 항목을 사후 숨기지 않는다).
- DB row 가 누락/롤백되면 service 단 default fallback (`ModerationThresholdService.DEFAULTS`)
  으로 자동 복귀 — 안전판.

후속 TODO:
- 임계치 변경 audit log (누가/언제/어떤 값으로) 는 본 PR 범위 밖 — 후속 PR.
- 임계치 변경 시점에 누적 PENDING 신고가 새 임계치를 이미 넘은 항목을 일괄 재평가하는 옵션은
  운영팀 합의 후 별도 PR.

### Audit log archive scheduler (PR68)

PR66 의 수동 archive + PR67 의 archive 조회 위에 옵션 스케줄러를 얹는다.

| 항목 | 값 |
| --- | --- |
| 기본 상태 | **OFF** (`audit_log_retention_scheduler_settings.enabled = false` seed) |
| 기본 cron | `0 30 3 * * *` (매일 03:30 KST, Asia/Seoul) |
| 한 번 실행 한도 | 1000건 (PR66 ARCHIVE_LIMIT 재사용) |
| 실패 처리 | application log warn/error, 다음 tick 정상 동작 |
| audit 기록 | scheduler 실행은 audit log 에 남기지 않음 (system actor 미지원). 수동 archive 만 `AUDIT_LOGS_ARCHIVED` 기록 |
| test profile | `@Profile("!test")` 로 bean 미등록 → SpringBootTest hang 방지 (PR50 패턴 준수) |

운영 절차:
1. ADMIN 콘솔의 "감사 로그 보존 정책" 카드 → 스케줄러 영역에서 **켜기** 클릭.
2. 토글한 ADMIN 의 id 가 `updated_by` 로 박힘 → 자동 archive 시 archive row 의 `archived_by` 로 재사용.
3. 매 cron tick 마다 service 가 settings 를 읽어 enabled=true 이면 archive 실행.
4. 결과는 application log: `audit-log-retention scheduler: archived={} cutoffAt={} remaining={}`.

cron 변경:
- DB 의 `cron` 컬럼은 표시 / 후속 PR 대비 보존. **현재는 application.yml 의
  `audit-log-retention.scheduler.cron` 또는 default `0 30 3 * * *` 가 실제 schedule 을 결정**.
- 따라서 cron 을 실시간 바꾸려면 application property 변경 + 재부팅이 필요. 동적 재등록은 후속 PR.

확인 방법:
- archive table 행 수 모니터링:
  ```sql
  SELECT COUNT(*) FROM moderation_audit_log_archive;
  SELECT MAX(archived_at) FROM moderation_audit_log_archive;
  ```
- ADMIN 콘솔 "아카이브" 탭 (PR67) 에서 최신 archived row 직접 확인.

## 7. Moderation audit log 보존 정책 (PR64)

PR61~63 에서 운영 액션을 `moderation_audit_logs` 테이블에 append-only 로 남기게 됐다.
무한 적재를 막기 위해 보존 정책을 명시. **본 PR 은 dry-run 만 제공** — 실제 삭제/archive 는
후속 PR.

| 항목 | 값 |
| --- | --- |
| 기본 보존 기간 | **365 일** (`ModerationAuditLogRetentionService.DEFAULT_RETENTION_DAYS`) |
| 허용 범위 | 30~3650 일 (`MINIMUM_RETENTION_DAYS` / `MAXIMUM_RETENTION_DAYS`) |
| 저장 방식 | 서비스 상수 — Flyway migration 없음. 운영 중 영구 변경 필요해지면 후속 PR 에서 KV / 전용 row 로 승격 |
| 조회 API | `GET /api/v1/admin/moderation/audit-log-retention?retentionDays=...` (ADMIN) |
| Dry-run 결과 | `cutoffAt`, `dryRunDeletableCount`, `oldestAuditLogCreatedAt`, `newestAuditLogCreatedAt` |
| 실제 삭제 | **수행하지 않음** — 본 PR 범위 밖 |

운영 절차:
1. ADMIN 콘솔의 "감사 로그 보존 정책" 카드에서 현재 cutoffAt + 삭제 예상 개수 확인.
2. 컴플라이언스 요구가 다르면 카드의 input 으로 임시 override 해 dry-run 다시 계산.
3. 실제 정리는 후속 PR (배치 job / scheduled task / archive table) 에 도입 — 안전 가드
   (확인 모달, soft delete window, archive 우선 등) 와 함께.

권장:
- 운영 데이터가 쌓이기 전에 retention 값을 합의해 두기 (incident 회고 / GDPR 요구 / 감사 보고
  주기와 균형).
- 첫 cleanup 실행 전 staging 에서 dry-run + 일부 row export(PR63) 로 외부 백업 확보.

## 8. 배포 전 마지막 체크

- [ ] §1 환경 변수 모두 secrets 에 들어가 있는가 (특히 `JWT_SECRET`, `TOSS_SECRET_KEY`,
      `AWS_SECRET_ACCESS_KEY`).
- [ ] DB 의 마이그레이션 권한 / 일반 권한 계정 분리.
- [ ] 첫 배포는 staging 에서 `ddl-auto=validate` + `flyway.enabled=true` 로 검증 끝났는가.
- [ ] readiness/liveness probe 가 k8s manifest 에 들어 있는가.
- [ ] `TOSS_WEBHOOK_SIGNATURE_REQUIRED=true` (PaymentHardeningCheck 가 부팅 차단).
- [ ] PR48 schema 승격을 위해 `reports` 중복 행 cleanup 완료.
- [ ] 로그 수집 (CloudWatch / Loki 등) 이 컨테이너 stdout 을 받고 있는가.

## 9. 후속 과제

- ~~신고 누적 자동 비공개 정책 (별도 PR).~~ → PR51 에서 도입, PR60 에서 DB 임계치로 승격, 위 §6 참고.
- ~~전체 `./gradlew test` hang 원인 분리~~ → PR50 에서 해결됨, 위 §5 참고.
- ~~Moderation 임계치 변경 audit log~~ → PR61 에서 도입, 위 §7 audit log 정책 참고.
- Audit log 실제 cleanup (배치 / scheduled task / archive table) — PR64 dry-run 후속.
- Audit log retention 값을 DB / KV 로 영구 저장해 운영 중 변경 가능하게 — PR64 후속 옵션.
- Actuator metrics/prometheus 노출 — 인증 게이트 (basic auth 또는 internal-only path) 와 함께.
- Flyway V2 — 운영 첫 검증 후 mismatch 보정.
