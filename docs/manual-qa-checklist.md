# CONTENIDO 수동 QA 체크리스트

릴리스 전 핵심 동선이 깨지지 않았는지 손으로 한 번 훑는 용도. 자동화 테스트는 서비스/통합 레벨에서 168 케이스가 있지만, 실제 SSE/세션/모바일 UI 가 함께 도는 흐름은 손으로 확인해야 합니다.

소요 시간: 약 25-35분 (한 사람) — 결제·환불 시나리오까지 포함.

## 본 문서 사용법

각 섹션은 다음 형식을 따릅니다:

- **목적** — 무엇을 확인하려는가 (한 줄)
- **사전 조건** — 계정, 데이터, 환경 변수 같은 시작 상태
- **체크리스트** — 클릭하거나 확인할 항목들
- **기대 결과** — (필요 시) 통과 판정 기준

각 체크박스에는 두 종류가 섞여 있습니다:

- 🖱 **브라우저 수동 테스트 필요** — 실제로 클릭/입력해야 확인되는 항목. 결제 / 환불 / SSE / 알림 / 라이브 효과는 거의 전부 이쪽.
- 📋 **정적 확인 가능** — 코드 / DB / devtools 패널 / 응답 페이로드만으로 통과 여부가 판정되는 항목. 환경 변수, accessibility 트리, multipart 응답 멱등성 등.

라벨이 명시되지 않은 항목은 기본 🖱 로 봅니다. 시간이 부족할 때는 🖱 만 먼저 돌리고 📋 는 다음 사이클로 미뤄도 됩니다.

플랫폼 전체 흐름 / 도메인 구조 / 결제·환불 정책 / moderation·audit 흐름은 [docs/architecture.md](architecture.md) 참고. 본 체크리스트는 그 위에 얹는 "행동 검증" 단계입니다.

## 사전 준비

1. 백엔드 + 프론트 둘 다 띄움 ([docs/dev-setup.md](dev-setup.md) 참고).
2. 브라우저 두 개 또는 시크릿창 사용 — **기획자 계정**과 **참가자 계정** 동시 로그인이 필요.
3. DB 는 빈 상태로 시작 권장 (`ddl-auto: create` 로 매 부팅마다 초기화). 더 빨리 비우려면 MySQL 컨테이너 재시작.
4. 시드 데이터를 만드는 빠른 절차는 [docs/seed-data.md](seed-data.md) 참고 (계정/채널/이벤트 한 세트 만들기 약 5분).

### 테스트 계정 만들기

회원가입 API 로 한 번에 3개 정도 만들어 둡니다 (`role` 은 PARTICIPANT 또는 CREATOR 만 지정 가능, ADMIN 은 직접 DB 수정 필요).

```
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"owner@test.com","password":"password1","nickname":"오너","phoneNumber":"01011111111","role":"CREATOR"}'

curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"staff@test.com","password":"password1","nickname":"스태프","phoneNumber":"01022222222","role":"CREATOR"}'

curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"member@test.com","password":"password1","nickname":"참가자","phoneNumber":"01033333333","role":"PARTICIPANT"}'
```

ADMIN 계정이 필요하면 DB 에서 직접 변경:

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'owner@test.com';
```

PARTICIPANT 가 기획자가 되어가는 동선까지 보고 싶으면 위 CREATOR 계정 생성을 생략하고 PARTICIPANT 로 가입한 뒤 `/creator/apply` 신청 → ADMIN 승인 흐름을 타도 됩니다.

## 핵심 동선 체크리스트

### 1. 회원가입 / 로그인
- [ ] `/` 에서 미인증 시 로그인 페이지 노출
- [ ] 회원가입 폼: 이메일 형식 오류, 비밀번호 < 8자, 닉네임 < 2자, 전화번호 형식 오류 — 각각 에러 토스트
- [ ] 회원가입 성공 → 로그인 → 홈 진입
- [ ] 새로고침 후에도 로그인 유지 (refresh 토큰)
- [ ] 로그아웃 → 토큰 정리 + 로그인 페이지

### 2. 기획자 채널 생성
- [ ] CREATOR 로 로그인 → `/creator` 진입
- [ ] 채널 없음 화면에서 채널 생성 폼 노출 (이름/카테고리/소개)
- [ ] 채널 이름 누락/카테고리 누락 → 폼 검증
- [ ] 생성 성공 토스트 → `/creator` 가 채널 보유 뷰로 전환
- [ ] 채널 카드 클릭 → `/channels/{id}` 상세 진입

### 3. 이벤트 생성 / 수정
- [ ] `/channels/{id}/events/new` 진입 (owner only)
- [ ] 필수 필드(제목/장소/이미지/시간/정원/참가비/환불정책/상세) 누락 검증
- [ ] 종료시간 < 시작시간 → `InvalidEventDateRangeException` 토스트
- [ ] 생성 성공 → `/events/{id}` 로 이동
- [ ] EventDetail 에서 owner 만 "이벤트 수정" 버튼 노출
- [ ] `/events/{id}/edit` 진입 → 기존 값 pre-fill
- [ ] 정원을 현재 참가자 수보다 적게 → `MaxParticipantsBelowCurrentException` 토스트
- [ ] 발급된 티켓이 있는 상태에서 참가비 변경 → `EventHasIssuedTicketsException` 토스트
- [ ] 수정 성공 → EventDetail 로 복귀 + 값 갱신
- [ ] PARTICIPANT/타 기획자 계정으로 `/events/{id}/edit` 직접 진입 → 권한 없음 화면

### 4. 참가 신청
- [ ] PARTICIPANT 계정으로 `/events/{id}` 진입
- [ ] "참가 신청하기" 버튼 → PENDING 상태로 전환 ("승인 대기 중" 배지)
- [ ] 이미 신청한 상태에서 재신청 → 버튼 비활성/숨김
- [ ] 채널 owner 가 본인 이벤트에 신청 시도 → `OwnerCannotApplyException` 토스트
- [ ] CLOSED 이벤트 신청 → `EventClosedException` 토스트
- [ ] 정원 가득 찬 이벤트 신청 → `EventFullException` 토스트
- [ ] 참가자가 본인 신청 취소 (PENDING) → CANCELED 상태로 전환
- [ ] APPROVED 인 상태에서 취소 → confirm 다이얼로그 → 정원 1 감소, 티켓 CANCELED

### 5. 승인 / 거절
- [ ] owner 로 `/creator` → 해당 이벤트의 "신청자 관리" → 신청자 목록
- [ ] 한 신청을 승인 → PENDING → APPROVED, 정원 +1, 알림 발송
- [ ] 한 신청을 거절(사유 입력) → PENDING → REJECTED, 사유 저장, 알림 발송
- [ ] 참가자 계정 새로고침 → `/events/{id}` 본인 카드가 "참가 확정" / "신청 거절" 로 갱신
- [ ] 알림 페이지에서 알림 클릭 → 해당 이벤트로 이동

### 6. 티켓 보기
- [ ] APPROVED 받은 참가자 → "내 티켓 보기" 버튼 노출
- [ ] `/tickets/{id}` 진입 → 패스/QR/체크인 코드 + 상태(PAID) 노출
- [ ] 이벤트 시작 전 취소 → 티켓이 CANCELED 로 전환
- [ ] USED 상태 티켓은 취소 시도 시 `TicketAlreadyUsedException`

### 7. 스태프 추가
- [ ] owner 로 채널 멤버 관리 화면 진입
- [ ] 다른 PARTICIPANT/CREATOR 계정의 이메일/닉네임으로 STAFF 추가
- [ ] ADMIN 계정을 STAFF 로 추가 시도 → `CannotAddAdminAsStaffException`
- [ ] 이미 멤버인 사용자 추가 시도 → `AlreadyChannelMemberException`
- [ ] owner 본인을 멤버에서 제거 시도 → `CannotRemoveOwnerException`
- [ ] STAFF 계정으로 로그인 → 해당 채널/이벤트의 신청자 관리/체크인이 가능한지 확인 (UI 노출 + 권한 통과)
- [ ] PARTICIPANT 가 스태프 추가 시도 → 권한 없음

### 8. 체크인 코드 입력
- [ ] 스태프 또는 owner 로 `/check-in` 진입
- [ ] 참가자 티켓의 체크인 코드 입력 → 체크인 성공 + 토스트
- [ ] 같은 코드 재입력 → `TicketAlreadyUsedException` 또는 동일 에러 메시지
- [ ] **buyer 본인이 자기 티켓 코드 입력 시도** → 권한 없음 (스태프/owner 만 체크인 가능)
- [ ] `/events/{id}#check-ins` 진입 → 체크인 현황 보드 자동 스크롤
- [ ] 체크인이 발생하면 EventDetail 의 체크인 카운트가 SSE 로 자동 갱신 (debounce 300ms)

### 9. 공지 작성 / 수정 / 삭제
- [ ] owner 로 채널 상세 → 공지 탭 → 새 공지 작성
- [ ] 채널 구독자에게 `NEW_POST` 알림 발송 확인 (다른 계정)
- [ ] 작성자/owner 본인은 수정/삭제 가능
- [ ] PARTICIPANT 또는 비owner 가 같은 공지 수정/삭제 시도 → `UnauthorizedException`

### 10. 알림 라우팅
- [ ] 헤더의 알림 아이콘에 unread 카운트 표시
- [ ] `/notifications` 진입 → 알림 목록 + 페이징
- [ ] 알림 카드 클릭 → `pathForTarget` 매핑대로 이동:
  - `events/*` → 해당 이벤트
  - `channels/*` (NEW_POST) → 채널 + `?tab=posts`
  - `tickets/*` → 티켓 상세
  - `creator-applications/*` → ADMIN 이면 `/admin`, 아니면 `/my`
- [ ] 클릭한 알림은 unread → read 로 즉시 전환 (optimistic)
- [ ] 모두 읽음 버튼 → unreadCount 0
- [ ] SSE 끊김 시 헤더에 `연결 중` / `실시간 연결 끊김` 상태 노출
- [ ] **같은 (targetType, targetId) 알림이 5초 내 여러 번 → 토스트 1번만** (예: 승인 직후 PARTICIPATION_APPROVED + TICKET_ISSUED 동시 도착)

### 11. 비밀번호 변경 후 재로그인
- [ ] 마이페이지(또는 설정) → 비밀번호 변경 진입
- [ ] 현재 비밀번호 누락/불일치 → 폼/토스트 에러
- [ ] 새 비밀번호 < 8자 → 폼 검증
- [ ] 새 비밀번호 == 현재 비밀번호 → 거부 메시지
- [ ] 변경 성공 토스트 → 그 즉시 또는 자동 로그아웃 후 로그인 페이지
- [ ] **기존 비밀번호로 로그인 시도 → 실패** (`AUTH_INVALID_CREDENTIALS`)
- [ ] 새 비밀번호로 로그인 → 성공, 홈 진입, 기존 채널/티켓/구독 상태 그대로 유지
- [ ] 변경 직후 기존 access 토큰으로 보호 API 호출 → refresh 흐름이 정상 동작하거나 401 후 로그인 페이지로 이동
- [ ] 회귀: 비밀번호 변경 후 SSE 알림 스트림이 새 토큰으로 재연결되는지

### 12. Admin 운영 콘솔 (PR72+73)

사전 조건: `role = 'ADMIN'` 계정으로 로그인. `/admin` 진입.

**탭 URL sync**
- [ ] `/admin` 진입 시 `?tab=overview` URL 자동 세팅
- [ ] `/admin?tab=reports` 직접 접근 → reports 탭이 활성
- [ ] `/admin?tab=invalid` → overview 탭으로 fallback
- [ ] 탭 클릭 → URL `?tab=` 업데이트 + 브라우저 히스토리 엔트리 생성
- [ ] 새로고침 후 같은 탭 유지
- [ ] 브라우저 뒤로가기/앞으로가기 → 탭 상태 동기화

**Lazy load / 상태 보존**
- [ ] 처음 진입 시 overview 탭만 로딩 → "불러오는 중…" 표시 후 데이터 채워짐
- [ ] reports 탭 첫 클릭 시 로딩 표시 → 데이터 채워짐
- [ ] 탭 A → 탭 B → 탭 A 이동 시 탭 A 상태가 보존됨 (불필요한 refetch 없음)
- [ ] 탭 B 에서 탭 A 의 데이터를 건드리는 액션 없음 (각 탭 독립)

**reports 탭**
- [ ] 필터(전체/후기/댓글/게시글/이벤트/채널) 클릭 → 목록 갱신
- [ ] PENDING 신고 카드 "숨김" 버튼 → 성공 토스트 + 카드의 "숨김" 배지 전환
- [ ] 이미 숨김인 카드 "숨김 해제" → 성공 토스트 + 배지 전환
- [ ] "기각" 버튼 → 신고 상태 DISMISSED 전환
- [ ] "해결 처리" 버튼 → 신고 상태 RESOLVED 전환
- [ ] 이미 처리된 신고(RESOLVED/DISMISSED)는 액션 버튼 미노출

**appeals 탭**
- [ ] PENDING 이의 제기 목록 로드
- [ ] "거절" 클릭 → prompt(사유) → 취소 시 아무 것도 안 함 → 확인 시 거절 처리 + 목록에서 제거
- [ ] "승인 (숨김 해제)" 클릭 → 성공 토스트 + 목록에서 제거
- [ ] `targetHidden === false` 인 항목 → "이미 해제됨" 배지 표시

**overview 탭**
- [ ] 통계(신고/숨김/이의제기 수치) + 라인 차트 표시
- [ ] 위험 채널 목록: "채널 제재" 버튼 → 사유 prompt → confirm → 성공 토스트 + 통계 갱신
- [ ] "제재 해제" → confirm → 성공 토스트
- [ ] 임계치 필드 수정 후 "임계치 저장" → 성공 토스트
- [ ] 임계치 1~100 범위 초과 입력 → 경고 토스트 (저장 안 됨)
- [ ] 운영 큐 → 숨김/숨김 해제/신고 기각·해결/이의 제기 승인·거절 각각 동작

**audit 탭**
- [ ] 현재 로그 / 아카이브 탭 전환
- [ ] 액션·대상 종류·대상 ID·운영자 ID·날짜 필터 적용 → 목록 갱신
- [ ] "필터 초기화" → 필터 리셋
- [ ] "상세" 버튼 → before/after JSON pretty-print 펼침 → "접기" 복귀
- [ ] "CSV 내보내기" → 파일 다운로드
- [ ] 아카이브 탭 → "아카이브 CSV 내보내기" → 파일 다운로드
- [ ] 페이지 이전/다음 버튼 동작

**retention 탭**
- [ ] 현재 보존 정책 수치 표시
- [ ] 보존 일수 입력 + "미리 계산" → 삭제 예상 개수 갱신
- [ ] "아카이브 미리보기" → 후보 수 표시
- [ ] 확인 텍스트 `ARCHIVE` 입력 → 실행 버튼 활성 → 클릭 → 성공 토스트
- [ ] 스케줄러 토글 ON/OFF → 상태 텍스트("예약 실행 중"/"예약 없음") 갱신
- [ ] cron 입력 변경 + "cron 저장" → 성공 토스트 (잘못된 식 → 오류 토스트)

### 13. 결제 플로우 (PR74)

사전 조건: 참가비 > 0 인 유료 이벤트가 존재하고 참가자 계정으로 로그인.

**이벤트 상세 CTA 상태**
- [ ] ADMIN 계정으로 이벤트 상세 진입 → sticky CTA(결제하기/신청하기) 미노출
- [ ] 이벤트 owner 계정으로 진입 → sticky CTA 미노출
- [ ] 유료 이벤트 미신청 상태 → "{금액}원 결제하고 참가하기" 버튼 활성
- [ ] PENDING 상태 → "승인 대기 중" 버튼 비활성
- [ ] APPROVED 상태 → "참가 확정" 버튼 비활성 + 티켓 있으면 "티켓 보기" 버튼 표시
- [ ] REJECTED 상태 → "신청 거절됨" 버튼 비활성
- [ ] 정원 마감(currentParticipants >= maxParticipants) → "정원 마감" + SOLD OUT 스탬프
- [ ] 종료 이벤트 → "종료된 이벤트" 버튼 비활성

**참가 취소 (PR75 — paid+APPROVED 가드)**
- [ ] 유료 이벤트 APPROVED → "참가 취소" 버튼 미노출, 대신 "취소·환불은 티켓 페이지에서 진행해주세요" 안내
- [ ] 유료 이벤트 APPROVED → "티켓 보기" 사이드 CTA → 티켓 페이지 → "환불 요청"(시작 시각 이전, PAID)
- [ ] 무료 이벤트 APPROVED 취소 → "발급된 티켓도 함께 취소됩니다." 확인 다이얼로그
- [ ] PENDING 취소 → 확인 다이얼로그 없이 바로 취소

**결제 페이지**
- [ ] 이벤트 상세 → "결제하기" → `/events/{id}/payment` 진입
- [ ] 결제 확인 다이얼로그에 이벤트 제목 포함: "[이벤트명]\n₩{금액} 결제하시겠어요?"
- [ ] 환불정책 미동의 시 결제 버튼 비활성
- [ ] 환불정책 동의 후 → 버튼 활성 → 결제 진행 가능
- [ ] 결제 중 버튼 중복 클릭 방지 (`aria-busy`, disabled)

**결제 성공 콜백 (`/payments/success`)**
- [ ] 유효한 쿼리 파라미터 → confirm 호출 → 성공 토스트 → 티켓 페이지 이동
- [ ] confirm 실패 → "결제 확인에 실패했어요" + "마이페이지로 이동" + "홈으로 돌아가기" 두 CTA 표시
- [ ] 마이페이지 CTA → `/my` 진입 (이미 결제된 경우 티켓 확인 가능)

**결제 실패 콜백 (`/payments/fail`)**
- [ ] `code=USER_CANCEL` → "결제를 취소하셨어요" 타이틀 + 안내 문구
- [ ] `code=INVALID_CARD_INFO` → "카드 정보를 확인해주세요" 타이틀
- [ ] 알 수 없는 code → 기본 타이틀 + Toss 원문 메시지 표시
- [ ] (PR75) URL 에 `eventId` 가 있으면 "다시 결제하기" → `/events/{eventId}/payment` 로 직진 (history.back 의존 안 함)
- [ ] `eventId` 없는 경우 fallback → `window.history.back()` 또는 `/`
- [ ] "홈으로" → `/` 로 이동

**MY 페이지 결제 탭 (PR75)**
- [ ] 결제 탭은 `paymentAttemptId` 가 있는 항목만 노출 (무료 티켓은 결제 탭에 안 보임)
- [ ] 무료 이벤트 참가 후 신청 탭에 "발급 완료" 티켓 노출
- [ ] 유료 결제 후 결제 탭에 영수증(주문번호/결제 금액/결제 수단) 노출
- [ ] 결제 탭 상태 필터(전체/결제완료/사용완료/환불됨/취소됨) 정상 동작

### 14. 환불 플로우 (PR77)

사전 조건: 유료 이벤트 결제 완료, 본인 계정으로 로그인. 시작 시각이 24h 이내인 이벤트도 한 건 준비.

**EventDetail → 티켓 페이지 진입**
- [ ] 유료 APPROVED 카드 상단에 "취소·환불은 티켓 페이지에서 진행해주세요" 안내(취소 버튼은 미노출)
- [ ] 안내는 ticketStatus 가 USED / REFUNDED / CANCELED 인 경우엔 노출되지 않음
- [ ] sticky CTA "티켓 보기" → `/tickets/{id}` 진입

**TicketDetail 환불 요청 (시작 시각 24h 초과)**
- [ ] "환불 요청" 버튼 활성, 환불 마감 chip 미노출
- [ ] 버튼 → confirm("정말 환불을 진행할까요?") → prompt(사유) → 성공 토스트 → 티켓 status REFUNDED 갱신
- [ ] 환불 후 버튼 자체가 사라짐, 영수증/이벤트 상세 보기 버튼만 남음

**TicketDetail 환불 요청 (시작 시각 24h 이내)**
- [ ] 상단에 환불 마감 카운트다운 chip ("N시간 M분 후 마감")
- [ ] 환불 요청 버튼 아래 inline warning "곧 환불 마감이에요. 이벤트 시작 후엔 환불할 수 없으니 서둘러주세요."
- [ ] 정상 환불 완료 → 토스트 + 상태 갱신

**TicketDetail 환불 요청 (시작 후)**
- [ ] 환불 요청 버튼 미노출
- [ ] "이벤트가 시작되어 환불 가능 시간이 지났어요. 부득이한 사정은 운영자에게 문의해주세요." copy 표시
- [ ] 환불 마감 chip 은 "환불 마감"

**중복 클릭 / 멱등**
- [ ] 환불 처리 중 버튼은 `disabled` + `aria-busy="true"` + spinner + "환불 처리 중..." 라벨
- [ ] 처리 중 더블 탭/엔터 키 → 추가 호출 발생 안 함 (handleRefund 의 refunding 가드)
- [ ] 이미 REFUNDED 상태에서 (theoretic, devtools 직접 호출) 다시 refund API 호출 시 backend 멱등 응답, frontend 는 다시 토스트만 표시

**실패 시 토스트 copy 매핑**
- [ ] 403 → "환불 권한이 없습니다"
- [ ] 409 + "이벤트가 이미 시작" → "환불 가능 시간이 지났어요" + "운영자에게 문의해주세요" 안내
- [ ] 409 + "이미 환불" or "취소된 티켓" → "환불할 수 없어요" + "이미 환불되었거나 환불할 수 없는 결제입니다."
- [ ] 그 외 409 → "환불할 수 없어요" + backend message
- [ ] 그 외 status → "환불 처리에 실패했어요" + backend message + "잠시 후 다시 시도해주세요."

**MY 페이지 결제 내역 표시 (환불 후)**
- [ ] 카드의 status 뱃지가 "참가 확정" 이 아닌 "환불됨"(danger) 으로 변경
- [ ] "티켓 보기" 버튼이 "영수증 보기"(secondary) 로 변경 — 클릭 시 동일하게 ticket page 진입
- [ ] 카드 자체의 "is-approved" highlight 제거 (테두리 처리)
- [ ] 결제 탭에서 "환불됨" 필터 클릭 → REFUNDED 항목만 표시

**Backend 멱등 / 가드 회귀**
- [ ] `.\gradlew.bat test` green (특히 PaymentService refund 관련)

### 15. 결제·환불·재신청 정합성 (PR76 / PR78 / PR79)

사전 조건: 유료 이벤트 1건 (시작 시각 24h+ 후, 정원 여유), 본인 계정으로 로그인.

**Backend 가드 (PR76 — paid APPROVED cancel 차단)**
- [ ] `PATCH /events/{id}/participations/me/cancel` 을 paid APPROVED 상태에서 직접 호출 → 409 `PaidParticipationCancelRequiresRefundException`
- [ ] 응답 메시지: "유료 이벤트는 티켓 환불 요청으로 취소해주세요."
- [ ] ticket / participation / currentParticipants 상태 변화 없음 (guard 가 가장 먼저 throw)
- [ ] 무료 APPROVED 는 기존대로 동작 (참가 취소 + 정원 -1 + 무료 티켓 CANCELED)
- [ ] paid PENDING 은 결제 전이므로 기존대로 취소 허용

**환불 cascade (PR78)**
- [ ] paid + APPROVED 사용자가 TicketDetail 환불 → ticket REFUNDED + participation CANCELED + currentParticipants -1
- [ ] MyPage 결제 탭에서 status 뱃지 "환불됨" + "영수증 보기" 버튼
- [ ] EventDetail 진입 → sticky CTA 가 "참가 신청하기" 또는 "다시 신청하기" 로 복귀 (티켓 보기 미노출)
- [ ] 이미 REFUNDED 인 ticket 에 (devtools 등으로) 환불 재호출 → backend 멱등 응답, currentParticipants 변화 없음
- [ ] 이미 CANCELED 인 participation 에 환불이 들어오면 정원 추가 감소 없음 (PR78 wasActive 가드)

**재신청 (PR79)**
- [ ] 환불로 participation 이 CANCELED 가 된 사용자 → EventDetail "다시 신청하기" CTA 활성
- [ ] 유료 이벤트: CTA → `/events/{id}/payment` 진입 → 새 PaymentAttempt(또는 기존 READY 멱등 응답) → 정상 결제
- [ ] 무료 이벤트: CTA → applyEvent → PENDING (재신청도 owner 에게 알림 1회)
- [ ] 이미 PENDING / APPROVED 인 상태에서 재신청 → 409 `AlreadyJoinedException`
- [ ] REJECTED 사용자도 재신청 가능 (reapply 시 rejectReason 초기화)
- [ ] 정원이 다 찼다면 reapply 시 EventFullException (정상)

### 16. 환불 알림 & 결제 라우트 가드 (PR81 / PR82 / PR83)

**환불 완료 알림 (PR81)**
- [ ] 유료 결제 후 환불 요청 성공 → buyer 의 알림 센터에 "환불이 완료되었어요" 1건 추가
- [ ] 알림 뱃지 라벨 "환불 완료" (warning tone)
- [ ] 알림 클릭 → `/tickets/{id}` 진입 (REFUNDED 티켓 화면)
- [ ] 이미 REFUNDED 인 ticket 에 (devtools 등) 환불 재호출 → 추가 알림 발생 안 함 (idempotent 멱등 분기는 markRefundedInternal 미진입)
- [ ] NotificationService 가 일시 실패해도 환불은 정상 처리됨 (runCatching best-effort)
- [ ] EventDetailPage 가 동일 ticketId 의 REFUND_COMPLETED 알림을 받으면 본문 + 본인 상태 refetch (CTA 가 즉시 "다시 신청하기" 로 갱신)
- [ ] MyPage 결제 탭이 REFUND_COMPLETED 알림을 받으면 영수증 카드 status 가 즉시 "환불됨" 으로 갱신

**결제 라우트 가드 (PR82)**
사전 조건: `/events/{id}/payment` 직접 URL 진입.
- [ ] 이미 PAID 티켓 보유 → "이미 결제된 티켓이 있어요" + "티켓 보기" / "이벤트 상세로" 두 CTA
- [ ] 이미 USED 티켓 보유 → 같은 화면이되 카피가 "이미 사용한 티켓이에요"
- [ ] participation PENDING → "승인 대기 중인 신청이에요" + "이벤트 상세로"
- [ ] participation REJECTED → "승인이 거절된 신청이에요" + "이벤트 상세로"
- [ ] 본인이 채널 owner → "본인이 운영하는 이벤트예요"
- [ ] 이벤트 CLOSED → "종료된 이벤트예요"
- [ ] 이벤트 시작 시각 지남 → "이미 시작된 이벤트예요"
- [ ] 무료 이벤트 → "무료 이벤트예요" (결제 페이지 자체 부적합)
- [ ] participation 없음 / CANCELED / 티켓 REFUNDED → 결제 폼 정상 렌더 (PR78/79 재결제 허용)
- [ ] getMyParticipation 실패 시에도 폼이 그대로 노출되어야 함 (가드 없는 fallback)

**TicketDetail copy (PR83)**
- [ ] REFUNDED 티켓 진입 → REFUNDED 스탬프 + "환불됨" 뱃지 + 환불 요청 버튼 미노출
- [ ] CANCELED 티켓 진입 → VOID 스탬프 + "취소됨" 뱃지 + 환불 요청 버튼 미노출
- [ ] 알림에서 진입했을 때도 같은 화면 (별도 hidden/404 분기 X)

### 17. EventDetail 남은 자리 라이브 강조 (PR91)
**목적**: 정원/잔여 자리 변화가 SSE refetch 로 들어왔을 때, 사용자가 변화한 사실을 즉시 알아챌 수 있게 한다.

**사전 조건**: 유료/무료 이벤트 1건 + 같은 이벤트에 대해 신청·승인·환불 등 currentParticipants 가 변할 수 있는 다른 사용자 1명 (시크릿창).

- [ ] 🖱 EventDetailPage 진입 직후 — 잔여 숫자에 highlight pulse 가 **켜지지 않음** (첫 로드는 트리거 X)
- [ ] 🖱 다른 시크릿창에서 같은 이벤트에 신청·승인을 진행해 `currentParticipants` 가 증가 → 본인 화면 잔여 숫자가 약 1.5초간 살짝 부풀며 색이 진해졌다가 원래대로 돌아옴
- [ ] 🖱 같은 시간대에 progress bar 주변에 옅은 box-shadow halo 가 1.5초 페이드인/아웃
- [ ] 🖱 같은 값으로 refetch 된 경우(다른 알림이 와도 currentParticipants 동일) highlight 가 **트리거되지 않음**
- [ ] 🖱 이벤트가 CLOSED 상태일 때 잔여 자리 row 자체가 노출되지 않음 (PR91 변경과 무관, 회귀 확인)
- [ ] 🖱 정원 가득 차서 잔여가 0 이 됐을 때 SOLD OUT 스탬프 + hero overlay 그대로 표시 (PR91 변경과 무관, 회귀 확인)
- [ ] 📋 OS 접근성 설정 "동작 줄이기 / Reduce Motion" ON → 잔여 숫자 pulse + 라이브 dot 점멸이 모두 비활성화 (devtools rendering 패널 "prefers-reduced-motion: reduce" emulation 으로 검증)
- [ ] 📋 Screen reader 가 잔여 숫자 영역을 polite 로 읽음 (devtools accessibility 트리에서 `aria-live="polite"` + 라벨 확인)

**기대 결과**: 잔여 자리는 SSE 가 새 값을 가져온 순간만 시각적으로 깜빡이고, 같은 값 refetch / 첫 로드 / 모션 감소 환경에서는 조용하다.

### 18. 알림 묶음 refetch 코얼레싱 (PR92)
**목적**: 승인 → 티켓 발급처럼 같은 동작이 짧은 시간에 두 알림으로 도착해도 화면 refetch 가 한 번으로 묶이는지 확인.

**사전 조건**: EventDetailPage / MyPage / TicketDetailPage / CreatorDashboardPage 에 진입 가능한 계정. devtools Network 탭으로 동일 API 의 동시 호출 수를 셀 수 있어야 함.

- [ ] 🖱 EventDetailPage 에서 같은 이벤트에 대해 다른 시크릿창이 신청 후 owner 가 즉시 승인 → SSE 로 두 알림이 거의 동시에 도착. devtools Network 에 `/events/{id}` GET 이 한 번만 발생
- [ ] 🖱 MyPage 진입 중 같은 시간대에 PARTICIPATION_APPROVED + TICKET_ISSUED 가 도착 → `getMyParticipations` 호출이 한 번만 발생
- [ ] 🖱 CreatorDashboardPage 에서 PARTICIPATION_REQUESTED + PARTICIPATION_APPROVED 가 묶음으로 도착 → `getCreatorStudio` 호출이 한 번만 발생
- [ ] 🖱 TicketDetailPage 에서 동일 ticketId 의 TICKET_CHECKED_IN 알림이 빠르게 두 번 (devtools 등으로 강제) → `getTicket` 호출이 한 번만 발생
- [ ] 🖱 펜딩 refresh 가 있는 상태에서 페이지를 이탈 → 타이머 cleanup 으로 unmount 후 추가 fetch 발생 안 함 (devtools Network 패널 확인)
- [ ] 📋 알림 UI(뱃지/카운트/리스트) 동작은 변경 없음 — 카드 수 / unread 카운트 / 뱃지 색이 PR91 시점과 동일

**기대 결과**: 같은 시간 창(기본 300ms) 에 들어온 묶음 알림은 한 번의 refetch 로 합쳐지고, 이탈 시 펜딩 타이머가 깨끗하게 정리된다.

### 19. 운영자 활동 요약 (PR93)
**목적**: Admin 운영 콘솔의 overview 탭에 추가된 "운영자 활동" 카드가 audit log 를 actor 단위로 정확히 집계하는지 확인.

**사전 조건**: ADMIN 계정 1명 + 같은 ADMIN 으로 최근 30일 내 hide / unhide / 채널 ban / appeal 처리 / 신고 처리 / 임계치 변경 액션을 최소 1건씩 실행한 상태. V9 가 적용된 환경이면 system actor 의 archive 실행분이 있을 수 있다.

- [ ] 🖱 `/admin?tab=overview` 진입 → "운영자 활동" 카드가 "위험 채널" 과 "운영 큐" 사이에 보임
- [ ] 🖱 위 사전 조건의 ADMIN 한 명만 데이터를 만들었다면 카드 row 1건만 표시 — 닉네임 + `#actorId` + total + 액션별 breakdown
- [ ] 🖱 breakdown 은 카운트가 0 인 액션은 표시되지 않음 (compact UI)
- [ ] 🖱 actorSystem true 인 row 는 닉네임 옆에 "System" 뱃지 표시 (스케줄러 자동 archive 실행 흔적)
- [ ] 🖱 audit row 가 0 건이면 "지난 30일 동안 운영자 활동이 없어요." copy 표시
- [ ] 🖱 backend 가 502 / 401 등으로 실패하면 "운영자 활동 데이터를 불러오지 못했어요." copy + overview 의 다른 카드(지표/위험 채널/임계치/큐) 는 정상 노출 — overview 전체가 막히지 않음
- [ ] 📋 `GET /api/v1/admin/moderation/actor-stats?limit=999` → 응답의 `limit` 필드가 50 으로 clamp 된 값
- [ ] 📋 limit=0 또는 음수 → 응답 limit=1
- [ ] 📋 default 호출 (`from`/`to` 미지정) → 응답 `from` 이 `to - 30 days` 와 같다
- [ ] 📋 audit log 0건 환경 → 응답 `items` 배열 비어 있음 (200 OK)

**기대 결과**: ADMIN 이 overview 한 화면에서 "지난 30일 동안 누가 얼마나 운영했는지" 를 한 번에 본다. system actor 자동 실행분이 사람 운영분과 섞이지 않고 분리되어 표시된다.

### 20. 알림 수신 설정 (PR95/PR96)
**목적**: 사용자가 NotificationType 별로 알림 수신을 끄거나 켤 수 있고, 끈 알림은 backend 발송 단계에서 차단되어 화면에 도착하지 않는지 확인.

**사전 조건**: 일반 사용자 계정 1명 + 같은 사용자에게 알림을 발생시킬 수 있는 다른 계정 (owner / commenter 등) 1명.

- [ ] 🖱 `/notifications` 진입 → 우측 상단에 "알림 설정" 버튼이 표시됨
- [ ] 🖱 "알림 설정" 클릭 → 패널 펼침 + 모든 NotificationType 이 체크박스로 노출 (기본 ON)
- [ ] 🖱 "알림 설정 닫기" 클릭 → 패널 접힘, 이후 다시 열면 마지막 상태 그대로 (재fetch 없이 캐시)
- [ ] 🖱 NEW_COMMENT 같은 type 의 체크박스 OFF → 즉시 PATCH 요청 발생 + "알림 수신 설정을 저장했어요" success toast
- [ ] 🖱 PATCH 진행 중 같은 체크박스 추가 클릭 → saving 가드로 변경 무시 (`aria-busy="true"` 노출, disabled)
- [ ] 🖱 PATCH 실패 (devtools 로 network 차단 등) → 체크박스가 이전 값으로 rollback + "설정 저장에 실패했어요" error toast
- [ ] 🖱 OFF 한 type 의 알림을 다른 계정으로 트리거 (예: NEW_COMMENT OFF 후 owner 가 본인 글에 댓글) → 본인 화면 NotificationsPage / 뱃지 / SSE 어디에도 알림 미도착
- [ ] 🖱 다시 ON → 이후 새로 도착하는 알림부터 정상 표시 (과거에 차단된 알림은 복구되지 않음)
- [ ] 🖱 새로고침 후 패널을 다시 열면 마지막 저장 상태가 그대로 표시 (DB에서 fetch)
- [ ] 📋 `GET /api/v1/notifications/preferences` 응답이 모든 NotificationType 을 포함 — row 가 없는 type 도 enabled=true 로 채워짐
- [ ] 📋 `PATCH /api/v1/notifications/preferences` request 에 같은 type 이 중복으로 들어가면 마지막 값 채택 (400 X)
- [ ] 📋 OFF 한 type 의 알림 발송 시 `notifications` 테이블에 row 자체가 INSERT 되지 않음 (backend 멱등성 + DB 부하 절감)
- [ ] 📋 preference 조회가 일시 실패해도 NotificationService 가 fail-open 으로 알림을 계속 발송 (회귀 가드)

**기대 결과**: 사용자가 알림 종류별로 ON/OFF 를 즉시 토글할 수 있고, OFF 한 알림은 발송 단계에서 차단된다. 실패 시에는 UI 가 이전 상태로 안전하게 복귀한다.

### 20a. 알림 묶음 토글 (PR99)
**목적**: NotificationsPage 알림 설정 패널의 "전체 / 카테고리별" 묶음 토글이 PR95 의 PATCH 엔드포인트를 한 번의 호출로 묶어 처리하고, 개별 체크박스 상태와 일관성을 유지하는지 확인.

**사전 조건**: 일반 사용자 계정 1명. 알림 설정 패널을 열어 둔 상태.

- [ ] 🖱 패널 상단에 묶음 영역 노출 — "전체 알림" + 참가/결제/콘텐츠/운영/시스템 5 카테고리 행, 각 행 우측에 "끄기/켜기" 버튼
- [ ] 🖱 초기 상태(모두 ON)에서 "전체 끄기" 클릭 → 모든 체크박스가 즉시 unchecked + 한 번의 PATCH 요청 발생 + success toast
- [ ] 🖱 이어서 "전체 켜기" 클릭 → 모든 체크박스가 즉시 checked + 한 번의 PATCH 발생
- [ ] 🖱 "참가 관련 끄기" 클릭 → `PARTICIPATION_*` (4) + `TICKET_*` (2) 만 OFF 로 토글, 다른 카테고리는 영향 없음
- [ ] 🖱 "결제 관련 끄기" → `REFUND_COMPLETED` 한 type 만 OFF
- [ ] 🖱 "콘텐츠 관련 끄기" → `NEW_EVENT` / `NEW_POST` / `NEW_COMMENT` / `NEW_LIKE` 4개만 OFF
- [ ] 🖱 "운영 알림 끄기" → `CHANNEL_BANNED` 한 type만 OFF
- [ ] 🖱 "시스템 알림 끄기" → `APPLICATION_APPROVED` / `APPLICATION_REJECTED` / `CHANNEL_UNBANNED` 3개만 OFF
- [ ] 🖱 카테고리 안의 type 을 개별 체크박스로 하나만 OFF → 해당 카테고리 버튼 라벨이 "{카테고리명} 끄기" 에서 "{카테고리명} 켜기" 로 자동 전환 (every-true → false)
- [ ] 🖱 같은 카테고리의 모든 type 을 개별 체크박스로 모두 ON 으로 복귀 → 버튼 라벨이 다시 "끄기" 로 전환
- [ ] 🖱 진행 중인 묶음 PATCH 가 끝나기 전 같은 카테고리 버튼 또는 그 안의 개별 체크박스 클릭 → saving 가드로 무시 (`aria-busy="true"` / `disabled`)
- [ ] 🖱 PATCH 실패 (devtools 로 차단) → 묶음 토글 직전 snapshot 으로 rollback (해당 카테고리 type 들만 복원) + danger toast
- [ ] 📋 묶음 PATCH 요청의 payload `preferences` 배열에 해당 카테고리 type 만 포함, 다른 카테고리 type 은 포함되지 않음 (devtools Network 탭)
- [ ] 📋 "전체 끄기" 클릭 시 payload 가 15 개 NotificationType 전체에 대해 `{ type, enabled: false }` 를 포함
- [ ] 📋 backend `user_notification_preferences` 테이블에서 카테고리 묶음 PATCH 결과가 row 단위로 정확히 반영 (개별 PATCH 와 같은 결과)
- [ ] 📋 PR95 fail-open 정책 유지: 묶음 PATCH 실패 시에도 알림 발송 흐름은 그대로 동작 (rollback 후 기존 row 기준)

**기대 결과**: 사용자가 한 번의 클릭으로 카테고리 단위 알림을 켜고 끌 수 있고, 개별 체크박스 상태와 묶음 버튼 라벨이 항상 일치한다. 실패 시 묶음 단위로 깨끗하게 복원된다.

### 20b. 알림 카드 Quick Mute + Undo (PR101)
**목적**: NotificationsPage 알림 카드의 "이 유형 끄기" 버튼이 해당 NotificationType 1건만 OFF 로 PATCH 하고, 5초 안에 되돌릴 수 있으며, 알림 설정 패널과 상태가 동기화되는지 확인.

**사전 조건**: 일반 사용자 계정 1명 + 같은 사용자에게 알림을 발생시킬 수 있는 다른 계정. 알림이 한 건 이상 있는 상태.

- [ ] 🖱 알림 카드 우측 상단에 "이 유형 끄기" 작은 버튼이 노출
- [ ] 🖱 알림 본문(카드 내부) 클릭 시 기존 라우팅 동작 그대로 유지 (mute 버튼 클릭이 라우팅으로 번지지 않음)
- [ ] 🖱 mute 버튼 클릭 → 단일 PATCH 발생, "{라벨} 알림을 껐어요" success toast, 화면 상단에 undo banner 5초 표시
- [ ] 🖱 5초 안에 undo banner 의 "되돌리기" 클릭 → 같은 type 을 enabled=true 로 PATCH, "{라벨} 알림을 다시 켰어요" success toast, banner 즉시 사라짐
- [ ] 🖱 5초 지나면 undo banner 가 자동으로 사라짐 (만료) — 이후 같은 type 의 알림 카드에는 "꺼짐" 배지만 표시
- [ ] 🖱 같은 type 의 다른 알림 카드 mute 버튼을 연속으로 클릭 → 첫 PATCH 진행 중에는 버튼 disabled + `aria-busy="true"`
- [ ] 🖱 서로 다른 type 을 빠르게 mute → undo banner 가 마지막 mute 한 type 으로 교체됨 (5초 카운트도 그 시점부터 재시작)
- [ ] 🖱 알림 설정 패널을 한 번도 열지 않은 상태에서 mute → PATCH 성공, 이후 패널을 열면 backend 에서 최신 상태로 fetch (해당 type 이 OFF 인 채로 표시)
- [ ] 🖱 알림 설정 패널이 열려 있는 상태에서 mute → 패널의 체크박스가 즉시 unchecked 로 동기화
- [ ] 🖱 같은 상황에서 undo → 체크박스가 즉시 checked 로 복귀
- [ ] 🖱 이미 enabled=false 인 type 의 알림 카드 → "이 유형 끄기" 버튼 대신 "꺼짐" 배지 노출, 액션 없음
- [ ] 🖱 mute PATCH 실패 (devtools 로 network 차단) → preferences snapshot rollback, danger toast, undo banner 안 뜸
- [ ] 🖱 undo PATCH 실패 → preferences snapshot rollback, danger toast
- [ ] 📋 mute PATCH payload `preferences` 배열에 해당 type 1건만 포함, 다른 type 미포함 (devtools Network)
- [ ] 📋 undo PATCH payload `[{ type: <같은 type>, enabled: true }]` 만 포함
- [ ] 📋 notification row 자체 / isRead 상태 / 라우팅(`pathForNotification`) 동작은 변경 없음 (회귀 가드)
- [ ] 📋 페이지 이탈 시 펜딩 undo 타이머가 cleanup 됨 (unmount 후 추가 setTimeout fire X)

**기대 결과**: 사용자가 알림 목록에서 한 번의 클릭으로 특정 유형 알림을 끌 수 있고, 실수했을 때 5초 안에 되돌릴 수 있다. 알림 설정 패널과 상태가 항상 일치한다.

### 20c. 알림 설정 "마지막 저장 시각" (PR104)
**목적**: 알림 설정 패널의 각 NotificationType 행 라벨 아래에 "마지막 저장 시각" 이 표시되고, 개별 토글 / 묶음 토글 / quick mute / undo 후 즉시 갱신되는지 확인. `UserNotificationPreference.updatedAt` 한 컬럼만 사용 (별도 history 테이블 없음).

**사전 조건**: 일반 사용자 계정 1명, 알림 설정 패널을 열 수 있는 상태.

- [ ] 🖱 패널을 처음 열면 row 가 없는 type 들은 라벨 아래에 "기본값" 표시
- [ ] 🖱 한 type 을 OFF 로 개별 토글 → "기본값" → "마지막 저장: 방금 전" 으로 즉시 갱신, 다른 type 은 그대로 "기본값"
- [ ] 🖱 다시 같은 type 을 ON 으로 토글 → "마지막 저장: 방금 전" 으로 갱신 (시간만 다시 카운트)
- [ ] 🖱 묶음 토글 (예: "참가 관련 끄기") → 해당 카테고리 6 type 모두 "마지막 저장: 방금 전" 으로 동시 갱신, 다른 카테고리 type 은 그대로
- [ ] 🖱 "전체 끄기" → 모든 type 이 "마지막 저장: 방금 전" (15개 모두 동시 갱신)
- [ ] 🖱 알림 카드에서 "이 유형 끄기" → 해당 type 의 패널 row 가 "방금 전" 으로 갱신 (패널이 열려 있을 때)
- [ ] 🖱 undo banner "되돌리기" → 같은 type 이 다시 "방금 전" 으로 갱신
- [ ] 🖱 새로고침 후 패널 재오픈 → 최근에 토글한 type 은 분/시간 단위 상대 시간 표시 (예: "5분 전")
- [ ] 🖱 7일 이상 지난 토글의 row 는 상대 시간이 아니라 로컬 날짜 (예: "2026. 5. 1.") 로 표시
- [ ] 📋 `GET /api/v1/notifications/preferences` 응답의 row 없는 type 은 `updatedAt: null`, row 있는 type 은 ISO LocalDateTime
- [ ] 📋 PATCH 후 응답에서 변경된 type 만 `updatedAt` 이 최신, 다른 type 의 `updatedAt` 은 그대로 유지 (partial update 회귀 가드)
- [ ] 📋 backend `user_notification_preferences` 테이블의 `updated_at` 컬럼이 변경된 row 만 갱신됨 (다른 row 의 timestamp 는 그대로)
- [ ] 📋 새 history / audit 테이블이 만들어지지 않음 (V11 등 신규 마이그레이션 없음)

**기대 결과**: 사용자가 알림 설정을 언제 마지막으로 만졌는지 가벼운 시각 단서를 얻는다. preference history / audit 가 도입된 것처럼 보이지 않게 "마지막 저장" 한 문구만 노출한다.

### 21. 알림 메타데이터 일관성 (PR97)
**목적**: NotificationType 별 라벨/tone/라우팅이 NotificationsPage 알림 카드와 알림 설정 패널에서 일치하고, 모든 enum 값에 정의가 있는지 확인.

**사전 조건**: NotificationsPage 진입 가능한 일반 사용자 계정.

- [ ] 📋 `frontend/src/utils/notificationMeta.ts` 의 `META` 가 `NotificationType` 모든 값(NEW_EVENT/NEW_POST/NEW_COMMENT/NEW_LIKE/APPLICATION_APPROVED/APPLICATION_REJECTED/PARTICIPATION_REQUESTED/PARTICIPATION_APPROVED/PARTICIPATION_REJECTED/PARTICIPATION_CANCELED/TICKET_ISSUED/TICKET_CHECKED_IN/CHANNEL_BANNED/CHANNEL_UNBANNED/REFUND_COMPLETED) 에 대해 label/tone 을 정의
- [ ] 🖱 NotificationsPage 알림 카드의 type 뱃지 라벨과 같은 페이지 "알림 설정" 패널의 라벨이 완전히 동일 (예: REFUND_COMPLETED → 두 곳 모두 "환불 완료")
- [ ] 🖱 모르는 type 응답이 도착해도(devtools 로 backend 응답 강제) "알림" 라벨 + neutral tone 으로 안전하게 표시 (페이지가 깨지지 않음)
- [ ] 🖱 알림 카드 클릭 시 라우팅이 PR97 이전과 동일 — events → /events/{id}, channels(NEW_POST) → /channels/{id}?tab=posts, channels(CHANNEL_BANNED) → CREATOR/ADMIN 은 /creator / 그 외는 /my, channels 기타 → /channels/{id}, tickets → /tickets/{id}, creator-applications → ADMIN 은 /admin / 그 외는 /my
- [ ] 🖱 알 수 없는 targetType 알림 카드는 클릭해도 라우팅되지 않고 읽음 처리만 (기존 fallback 동작 유지)

**기대 결과**: 라벨/tone/path 가 단일 모듈(`notificationMeta.ts`) 에서 정의되어 두 화면이 정의를 공유한다. 새 NotificationType 추가 시 한 곳만 수정하면 두 곳 모두 반영된다.

### 22. ADMIN 강제 환불 운영 도구 (PR106)
**목적**: USED 티켓 / 시작 후 PAID 티켓 등 일반 환불 경로로 처리할 수 없는 케이스를 ADMIN 이 전액 환불 처리할 수 있는지 + audit 기록 + buyer 알림이 모두 동작하는지 확인.

**사전 조건**: ADMIN 계정 1명. 환불 대상 티켓 (PAID 또는 USED, 결제 attempt PAID 상태) 1건 이상.

- [ ] 🖱 `/admin?tab=payments` 진입 → "운영 결제 도구" 섹션이 노출되고 ticketId 입력 + 사유 textarea + "강제 환불 실행" 버튼이 보임
- [ ] 🖱 ticketId 가 비어 있거나 0 이하 / 사유가 비어 있음 → "강제 환불 실행" 버튼 disabled
- [ ] 🖱 사유 500자 초과 시 input 자체가 자르거나 (`maxLength=500`) 글자 수 카운터에 도달
- [ ] 🖱 PR112 — reason 아래에 "확인 문구" 입력 필드 + help "강제 환불을 실행하려면 `REFUND` 를 정확히 입력하세요 (대소문자 구분)." 노출. ticketId / reason 유효해도 확인 문구 미입력이면 실행 버튼 disabled
- [ ] 🖱 PR112 — 소문자 `refund`, 앞뒤 공백만 있는 `  ` 등 정확히 일치하지 않는 입력 → 버튼 여전히 disabled + 확인 입력에 `aria-invalid="true"` 및 빨간 border 강조 (devtools accessibility tree 에서 invalid 표시 확인)
- [ ] 🖱 PR112 — 정확히 `REFUND` 입력 + ticketId / reason 모두 valid → 실행 버튼 enabled + `aria-invalid` 제거
- [ ] 🖱 PR112 — 정확히 `REFUND` 앞뒤로 공백이 있어도 (`  REFUND ` 등) trim 후 일치하면 활성 (붙여넣기 편의)
- [ ] 🖱 PR111 — 섹션 상단에 안내 bullet 3종 노출: "전액 환불만 가능 (부분 환불 미구현)" / "USED·시작 이후 티켓도 처리됨 — 일반 환불 가드 우회" / "처리 내역은 감사 로그에 기록됨"
- [ ] 🖱 PR111 — reason textarea 하단 help 텍스트 "운영 사유는 감사 로그(audit log)에 기록되고 사용자 알림에는 노출되지 않습니다." 노출 + textarea 에 `aria-describedby` 로 연결 (devtools accessibility tree 에서 describedby 확인 가능)
- [ ] 🖱 PR111 — ticketId input 에 `id` + label `htmlFor` 연결, reason textarea 에도 동일 (devtools accessibility tree 에서 label 이름이 input 에 노출됨)
- [ ] 🖱 유효한 입력 후 버튼 클릭 → confirm dialog 본문에 "전액 환불만 가능" / "USED·시작 이후 처리" / "감사 로그 기록" / "알림에 사유 미노출" 4 항목이 한 줄씩 모두 표시
- [ ] 🖱 confirm 취소 → 아무 요청도 발생하지 않음
- [ ] 🖱 confirm 확인 → backend POST 호출 후 "강제 환불 처리 완료" success toast + "마지막 처리 결과" 카드에 ticketStatus=REFUNDED, 금액, PG, 처리 시각, 사유 노출
- [ ] 🖱 PR112 — 성공 후 ticketId / reason / 확인 문구 세 필드 모두 비어 있는 상태로 리셋 (결과 카드만 유지). 다음 강제 환불은 모든 필드 재입력 + REFUND 재입력 필요
- [ ] 🖱 PR112 — 실패 (409 / 404 / 403 / 502 / 기타) 토스트 노출 후 세 필드 모두 유지 — 운영자가 원인 수정 (예: ticketId 정정) 후 동일 reason / 확인 문구로 재시도 가능
- [ ] 🖱 PR111 — 결과 카드의 라벨/값 grid 표시: "티켓 ID #N", "환불 금액 ₩12,345" (통화 포맷), "결제 수단 토스페이먼츠 / PortOne / Mock (테스트)" (provider 라벨 매핑), "결제 시도 ID #N", "처리 시각 YYYY-MM-DD HH:mm" (ko-KR 로컬), "PG 결제 키" 6칸 노출. providerPaymentKey 가 null 이면 "—" 표시
- [ ] 🖱 PR111 — 결과 카드 상단 우측에 ticketStatus Badge (REFUNDED 일 때 success tone, 그 외 neutral)
- [ ] 🖱 PR111 — 결과 카드 `role="status"` + `aria-live="polite"` (devtools accessibility tree 에서 status role 확인)
- [ ] 🖱 USED 티켓 ID 입력 → 강제 환불 성공 (일반 경로의 `TicketAlreadyUsedException` 없이 통과)
- [ ] 🖱 시작 후 (`event.startAt < now`) PAID 티켓 → 강제 환불 성공 (일반 경로의 `RefundDeadlinePassedException` 없이 통과)
- [ ] 🖱 이미 REFUNDED 인 티켓 → "이미 환불되었거나 환불할 수 없는 티켓입니다." danger toast (409, 멱등 응답 아니라 명시적 차단 — 실수 방지)
- [ ] 🖱 CANCELED 티켓 → "이미 환불되었거나 환불할 수 없는 티켓입니다." danger toast (409)
- [ ] 🖱 결제 attempt 가 없거나 status≠PAID → 409 + 동일 카피
- [ ] 🖱 ticketId 가 존재하지 않음 → "티켓을 찾을 수 없습니다." danger toast (404)
- [ ] 🖱 ADMIN 이 아닌 계정으로 endpoint 직접 호출 (devtools) → 403 + "ADMIN 권한이 필요합니다."
- [ ] 🖱 PG gateway 가 Failure 응답 (devtools mock) → "PG 환불 처리에 실패했습니다." (502)
- [ ] 🖱 PR111 — danger toast 의 `message` 영역에 backend 의 원문 message 가 details 로 그대로 보존 (사용자 친화 카피는 title, 디버깅용 message 는 details)
- [ ] 🖱 강제 환불 성공 후 buyer 의 알림 센터에 `REFUND_COMPLETED` 알림 1건 추가 (메시지는 일반 환불과 동일 — 운영 사유 미포함, 내부 정보 보호)
- [ ] 🖱 같은 시간대에 buyer 가 `/tickets/{id}` 진입 시 ticket 이 REFUNDED 로 표시 + 환불 요청 버튼 미노출
- [ ] 🖱 EventDetailPage 가 buyer 의 화면에서 자동 refetch → CTA 가 "다시 신청하기" / "참가 신청하기" 로 복귀
- [ ] 🖱 buyer 의 MyPage 결제 탭 영수증 카드 status 가 "환불됨" 으로 갱신
- [ ] 📋 `moderation_audit_logs` 에 `action=TICKET_FORCED_REFUNDED` row 1건 INSERT, `actor_id=관리자 ID`, `reason=입력한 사유`, `after_value` JSON 에 ticketId/paymentAttemptId/ticketStatus/amount 포함
- [ ] 📋 `target_type` 컬럼은 NULL (ReportTargetType 에 TICKET 이 없음)
- [ ] 📋 `tickets` 테이블의 해당 행 `status` PAID/USED → REFUNDED, `payment_attempts.refunded_at` / `refund_reason` 갱신
- [ ] 📋 `events.current_participants` 가 -1 (participation 이 active 였던 경우; PR78 가드 그대로)
- [ ] 📋 `event_participations` 의 해당 row 가 APPROVED → CANCELED
- [ ] 📋 일반 사용자 환불 (`POST /tickets/{id}/refund`) 의 deadline / USED 가드는 그대로 동작 — buyer 가 같은 USED 티켓을 일반 경로로 환불 시도 시 `TicketAlreadyUsedException` 409 (회귀 가드)
- [ ] 📋 PG gateway 가 Failure 응답을 보낸 경우 (devtools 로 mock) → 502 "PG 환불 처리에 실패했습니다." + ticket 상태는 보존 (PAID/USED 그대로, audit 기록 없음)
- [ ] 📋 새 마이그레이션 없음 (V10 까지만 존재). `ModerationAuditAction.TICKET_FORCED_REFUNDED` enum 추가만 코드 변경.
- [ ] 📋 PR112 — request payload (Network 탭) 는 `{ "reason": "..." }` 만 포함. `confirmText` 가 backend 로 전송되지 않음 (클라이언트 잠금)
- [ ] 🖱 PR113 — 강제 환불 1건 처리 후 `/admin?tab=audit-logs` 진입 → 액션 select 위에 "빠른 필터" 라벨 + "강제 환불" 칩 노출
- [ ] 🖱 PR113 — "강제 환불" 칩 클릭 → 칩이 active style (primary fill) + 액션 select 가 "강제 환불" 로 자동 갱신 + 목록이 `TICKET_FORCED_REFUNDED` row 만 표시 + 현재 페이지가 1 페이지로 reset (기존 페이지가 2 이상이었다면)
- [ ] 🖱 PR113 — TICKET_FORCED_REFUNDED row 의 액션 Badge 가 "강제 환불" 라벨 + warning tone, AUDIT_LOGS_ARCHIVED 라벨이 있는 row 가 있다면 "감사 로그 아카이브" + neutral tone 으로 표시 (PR65~PR106 enum 동기화)
- [ ] 🖱 PR113 — 칩 active 상태에서 row "상세" 클릭 → `afterValue` JSON pretty-print 에 `ticketId`, `paymentAttemptId`, `ticketStatus`, `amount` 4 키가 들어 있음. 별도 backend 검색 없이 row detail 에서 확인 가능
- [ ] 🖱 PR113 — active 상태에서 칩 재클릭 → 필터 해제 (action='' 로 복귀, 전체 로그 표시)
- [ ] 🖱 PR113 — "필터 초기화" 클릭 → 칩 active 도 함께 해제 (`auditFilters.action='' ` 동기화)
- [ ] 🖱 PR113 — 액션 select dropdown 옵션에도 "강제 환불" / "감사 로그 아카이브" 추가됨 (select 와 chip 둘 다에서 같은 backend action 값 전송)
- [ ] 🖱 PR113 — `aria-pressed` 속성이 칩에 적용 (devtools accessibility tree 에서 toggle button 으로 인식)

**기대 결과**: ADMIN 이 일반 환불 경로로 막혀 있던 케이스를 명시적 사유 기록과 함께 한 곳에서 처리할 수 있다. 환불 cascade, buyer 알림, audit 기록이 모두 동일 트랜잭션에서 일관되게 적용된다.

### 23. 운영자 활동 강제 환불 카운트 (PR109)
**목적**: PR106 의 강제 환불 audit 가 PR93 의 운영자 활동 요약(`/admin?tab=overview`) 의 actor 카드에 별도 카운트로 표시되는지 확인. 신규 endpoint / 마이그레이션 없이 표시만 추가.

**사전 조건**: ADMIN 계정 1명 + 같은 ADMIN 으로 최근 30일 내 `TICKET_FORCED_REFUNDED` audit row 가 1건 이상 누적된 상태 (PR106 의 `/admin?tab=payments` 에서 USED 티켓 강제 환불 1회 이상 실행).

- [ ] 🖱 `/admin?tab=overview` 진입 → "운영자 활동" 카드 row 의 breakdown 에 "강제 환불 N" 표시 (N >= 1)
- [ ] 🖱 강제 환불을 한 번도 실행하지 않은 ADMIN row 에서는 "강제 환불" 항목이 노출되지 않음 (`row.forcedRefundCount === 0` 일 때 미표시)
- [ ] 🖱 같은 ADMIN 이 추가로 1건 더 강제 환불 처리 → overview 재로드 시 카운트가 +1 (예: 1 → 2)
- [ ] 🖱 강제 환불 처리는 `totalActionCount` 에도 포함됨 — breakdown 합계가 total 과 일치 (또는 total 이 그 이상)
- [ ] 🖱 다른 액션(hide / ban / report decision / archive 등) 의 카운트는 강제 환불을 추가해도 영향 받지 않음
- [ ] 📋 `GET /api/v1/admin/moderation/actor-stats` 응답 JSON 의 각 `items[].forcedRefundCount` 가 number 타입으로 존재 (row 없어도 0)
- [ ] 📋 `moderation_audit_logs` 에 `TICKET_FORCED_REFUNDED` row 가 N건 있을 때 응답의 `forcedRefundCount` 도 N
- [ ] 📋 신규 endpoint / 마이그레이션 없음 — DTO 한 필드 + service 한 줄 + frontend 한 줄만 변경

**기대 결과**: 운영자가 한 화면에서 본인이 처리한 강제 환불 건수를 다른 운영 액션과 함께 한눈에 확인할 수 있다. audit 데이터를 그대로 활용해 추가 fetch 부담이 없다.

## 회귀 체크 (선택)
- [ ] 모바일 사이즈(420px) 로 줄여도 레이아웃이 깨지지 않음
- [ ] 새로고침 후에도 SSE 가 자동 재연결
- [ ] 새로고침 후에도 토스트/모달이 떠 있지 않음 (잔여 상태 없음)
- [ ] `./gradlew.bat test` 168/168 green
- [ ] `cd frontend; npm run build` 성공

## 통과 기준

핵심 동선 (1~11) 은 매 릴리스마다 전부 ✅. 결제/환불 흐름이 바뀐 릴리스에서는 13~16 도 전부 ✅. 운영 콘솔이 바뀐 릴리스에서는 12 + 19 도 전부 ✅. EventDetail / 알림 / 라이브 효과가 바뀐 릴리스에서는 17~18 추가. 한 줄이라도 빨간색이면 그 항목의 이슈를 먼저 처리합니다.

📋 항목만 회귀로 정적 확인하고 🖱 만 다음 사이클로 미루는 것은 허용. 반대로 🖱 만 돌리고 📋 를 영구 미루는 것은 금지 (감시되지 않는 누락 위험).
