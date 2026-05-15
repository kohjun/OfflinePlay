# WOYA — CONTENIDO

오프라인 이벤트 운영자가 채널을 열고, 참가자를 모집·승인·체크인하는 모바일 MVP.

- **Backend**: Spring Boot 3.3 (Kotlin 1.9.24, Java 21) + JPA/MySQL + Redis + Elasticsearch
- **Frontend**: Vite + React 18 + TypeScript (모바일 우선 420px 프레임)
- **Auth**: JWT (access + refresh) + Spring Security
- **Realtime**: SSE 알림 스트림

## 빠른 시작

```
# 백엔드
./gradlew.bat bootRun

# 프론트
cd frontend
npm install
npm run dev
```

자세한 셋업/테스트/문제해결은 [docs/dev-setup.md](docs/dev-setup.md) 참고.

## 테스트

```
./gradlew.bat test          # 백엔드 168 tests
cd frontend; npm run build  # 프론트 typecheck + 빌드
```

## 문서

- [docs/dev-setup.md](docs/dev-setup.md) — 로컬 개발 환경
- [docs/seed-data.md](docs/seed-data.md) — 수동 QA 용 seed 데이터 만드는 절차
- [docs/manual-qa-checklist.md](docs/manual-qa-checklist.md) — 릴리스 전 수동 QA 동선
- [docs/payment-refund-policy.md](docs/payment-refund-policy.md) — 결제/환불 정책 (PG 도입 전)
- [docs/kafka-outbox-plan.md](docs/kafka-outbox-plan.md) — Kafka outbox 도입 설계 (예정)
