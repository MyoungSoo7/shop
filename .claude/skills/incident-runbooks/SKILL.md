---
name: incident-runbooks
description: 정산 온콜 러너북 — 프로젝션 lag, outbox 적체, 컨슈머 정지, 스케줄러 락 문제의 진단·판단 기준. 장애/온콜/알람 키워드에서 로드.
---

# 온콜 러너북

## 1. 프로젝션 lag (runbook: settlement-projection-lag)

**증상**: `settlement_projection_amount` 가 order 원천 대비 계속 벌어짐, 대사 알람.

```
진단: projection_status() → 어느 뷰(payment_view/order_view/...)가 뒤처지는지 특정
      outbox_status('order') → pending 적체면 발행측, 정상이면 소비측
판단: 발행측 → outbox 폴러 로그 (claim 실패, Kafka 연결)
      소비측 → 해당 컨슈머 그룹 lag + DLT 적체 (컨슈머 예외 반복이면 poison message 의심)
조치: poison message → DLT 로 격리 후 리플레이 (멱등 보장으로 안전)
      대량 유실 → order 의 projectionbackfill 모듈로 백필 (직접 INSERT 금지)
```

## 2. Outbox 적체

**증상**: `outbox.pending.count` 단조 증가.

- 폴러 자체가 안 도는지(스케줄러 락) vs 발행이 실패하는지(`outbox.failed.count` 동반 상승) 구분.
- 스케줄러가 매 tick 실패하면 ShedLock 테이블 존재 여부 확인 (과거 장애: shedlock 마이그레이션 누락).
- 브로커 문제면 Redpanda 상태 확인 — 컨테이너/네트워크부터.

## 3. 컨슈머 중복/유실 의심

- 중복: 3단 멱등 방어 중 어느 층 로그가 찍혔는지 (idempotency-and-events skill 의 표).
- 유실: DLT 적체 확인 → 리플레이. "재발행" 전에 반드시 멱등 체크가 있는 컨슈머인지 확인.

## 4. 공통 수칙

- 모든 조회는 MCP 도구로 — 운영 DB 직접 접속 금지.
- 복구 조치 중 데이터를 만드는 행위(INSERT/UPDATE)는 에이전트가 직접 하지 않는다.
  백필·리플레이·조정은 **기존 운영 경로**(projectionbackfill, DLT replay, adjustment API)로만 제안하라.
- 조치 후에는 `recon_run(당일)` 로 대사 재확인하고, 결과를 타임라인(감지→진단→조치→검증)으로 보고하라.
