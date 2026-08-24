---
name: compliance-review
description: 금융 컴플라이언스 관점 코드 리뷰 기준 — PII 마스킹, 감사로그, 이력 보존, 권한. PR 리뷰·/compliance-scan 실행 시 로드.
---

# 컴플라이언스 리뷰 기준

전자금융감독규정·개인정보보호법 관점에서 정산 코드 diff 를 스크리닝하는 기준.
발견 항목은 심각도(BLOCK/WARN)와 근거 조항 성격을 붙여 보고한다.

## 1. 민감정보 (BLOCK)

- 로그·예외 메시지·이벤트 페이로드에 다음이 평문으로 들어가면 차단:
  계좌번호, 주민등록번호, 카드번호, 실명+연락처 조합.
- 마스킹은 `shared-common` `common.audit` 의 PII 마스킹 유틸 경유만 인정.
- toString()/@ToString 이 민감 필드를 포함하는 엔티티 → WARN (로그 유입 경로).
- 테스트 픽스처의 실제 계좌/주민번호 패턴 → BLOCK (더미 패턴으로 교체 지시).

## 2. 이력 보존 (BLOCK)

- `settlements`·`ledger_entries`·`payouts`·`settlement_adjustments` 에 대한
  UPDATE/DELETE SQL, JPA `@Modifying` 갱신, 스냅샷 컬럼(`commission_rate`) setter → 차단.
- Flyway 로 과거 버전 파일을 수정하는 diff → 차단 (신규 버전 파일로만).
- 보존 연한: 정산·원장 데이터 삭제 배치를 추가하는 PR 은 보존 정책 문서 링크 없으면 WARN.

## 3. 감사 추적 (WARN)

- 운영자 행위 API (PG 대사 승인/거절, 지급 재시도, 조정 생성)에 조작자 식별
  (`currentOperatorId()` 패턴)과 감사로그 적재가 없으면 지적.
- 사유(note) 없는 거절/취소 API 설계 → 지적 (기존 코드 표준: 사유 필수).

## 4. 권한 (BLOCK)

- `/admin/**` 컨트롤러가 SecurityConfig 의 ADMIN 강제 밖에 노출되는 라우팅 → 차단.
- 내부 API (`/internal/**`) 가 gateway 라우트에 추가되는 diff → 차단
  (내부 API 는 미라우팅 + `X-Internal-Api-Key` 이중 방어가 원칙).
- Actuator 엔드포인트 인증 해제 → 차단.

## 5. 보고 형식

```
[BLOCK] <파일:라인> <규칙> — <한 줄 근거>
[WARN]  <파일:라인> <규칙> — <한 줄 근거>
통과: 검사한 관점 목록 (민감정보/이력/감사/권한)
```

오탐 가능성이 있으면 BLOCK 대신 WARN + "사람 확인 필요"로 낮춰라. 단, 평문 주민번호는 예외 없이 BLOCK.
