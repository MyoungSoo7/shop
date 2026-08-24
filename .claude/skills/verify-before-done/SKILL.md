---
name: verify-before-done
description: "완료" 선언 직전 검증 절차 — HARNESS.md DoD 게이트를 실제 실행해 증거를 수집하고 작성·검증 분리를 지킨다. 작업 마무리·커밋 직전, "됐다/완료/통과할 것" 이라고 쓰고 싶어질 때 로드.
---

# Verify Before Done (완료는 주장이 아니라 게이트 출력이다)

## 철칙

**실행하지 않은 게이트를 "통과했을 것"이라고 쓰지 않는다.** 완료 보고의 근거는 LLM 의 확신이
아니라 기계 판정의 출력이다. 체크리스트 정본은 HARNESS.md `## 완료 판정(DoD)` — 여기 복제하지
않는다. 이 스킬은 그 정본을 **실행하는 절차**다.

## 절차

1. **해당 게이트 식별** — 이번 변경이 건드린 축을 DoD 항목과 대조한다
   (모듈 테스트/커버리지는 항상 · MSA 경계 변경 시 ArchUnit · 토픽 변경 시 계약 테스트 · 등).
2. **게이트 실행 + 출력 확보**:
   ```bash
   ./gradlew :<module>:test :<module>:jacocoTestCoverageVerification   # 변경 모듈 전부
   node scripts/harness/guard.mjs --staged                             # 돈 경로 가드
   node scripts/harness/harness-audit.mjs                              # 하네스·문서를 만졌다면
   ```
3. **실패 시** — 완료 보고로 넘어가지 말고 📘`debugging-discipline` 으로 회귀한다.
   실패를 숨긴 채 "대부분 통과" 로 뭉개지 않는다.
4. **작성·검증 분리** — 설계 판단이 필요한 변경(돈 경로·경계·계약)은 같은 컨텍스트에서
   자기 승인하지 않는다. `code-reviewer`/`verifier` 별도 패스 또는 해당 리뷰 에이전트
   (🤖`hexagonal-arch-reviewer`·`event-contract-reviewer`·`gl-ledger-auditor`)로 증거를 받는다.
5. **문서 후속** — 휘발성 수치를 문서에 적었다면 재현 명령을 병기한다(값만 적으면 즉시 드리프트).

## 완료 보고 형식

"완료" 문장에는 반드시 병기한다:

- **실행한 게이트**와 결과 요지 (예: `:order-service:test` 2,506건 통과, JaCoCo LINE 92%)
- **실행하지 않은 게이트**와 사유 (예: Docker 없음 → Testcontainers IT skip)
- 실패·스킵·미검증 축 — 숨기지 않는다. 부분 완료는 부분 완료라고 쓴다.

## Red Flags (이 생각이 들면 중단 신호)

| 합리화 | 실제 |
|---|---|
| "간단한 변경이라 테스트 생략해도" | 간단한 변경이 회계를 깬 전례가 이 저장소의 가드 역사다 |
| "로컬에서 됐으니 CI 도 될 것" | CI 는 훅 미설치·`--no-verify` 우회까지 재차단한다 — 다른 환경이다 |
| "게이트는 커밋 후에 돌리자" | pre-commit 가드가 막는다 — 순서를 바꿀 수 없다 |
| "지난번에 통과했으니 이번에도" | 게이트는 매 변경마다 실행한다 — 캐시된 확신은 증거가 아니다 |
