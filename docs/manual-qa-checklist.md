# CONTENIDO 수동 QA 체크리스트

릴리스 전 핵심 동선이 깨지지 않았는지 손으로 한 번 훑는 용도. 자동화 테스트는 서비스/통합 레벨에서 168 케이스가 있지만, 실제 SSE/세션/모바일 UI 가 함께 도는 흐름은 손으로 확인해야 합니다.

소요 시간: 약 20-25분 (한 사람).

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

## 회귀 체크 (선택)
- [ ] 모바일 사이즈(420px) 로 줄여도 레이아웃이 깨지지 않음
- [ ] 새로고침 후에도 SSE 가 자동 재연결
- [ ] 새로고침 후에도 토스트/모달이 떠 있지 않음 (잔여 상태 없음)
- [ ] `./gradlew.bat test` 168/168 green
- [ ] `cd frontend; npm run build` 성공

## 통과 기준

위 1-10 모두 ✅ 면 릴리스 가능. 한 줄이라도 빨간색이면 그 항목의 이슈를 먼저 처리합니다.
