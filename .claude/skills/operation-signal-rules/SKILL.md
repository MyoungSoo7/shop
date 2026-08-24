---
name: operation-signal-rules
description: 운영 관제 규칙 — 인시던트 라이프사이클·활성 유일성·refire 병합, opssignal(절대 throw 금지·Outbox 미사용·fire-and-forget), 신호버킷 5분 UPSERT·failure_rate, webhook 항상 200. operation-service 로직 작성·리뷰 시 로드.
---

# 운영 관제 규칙 (operation-service)

두 Bounded Context: **incident**(인시던트 라이프사이클) + **signal**(신호 버킷 집계). 발행 머시너리는 shared-common `common.opssignal`.
port 8092, lemuel_operation(스키마 opslab 재사용). `/api/ops/**` JWT ADMIN 전용.

## 인시던트 라이프사이클 (incident BC)

```
OPEN → ACKNOWLEDGED → RESOLVED | FALSE_POSITIVE   (RESOLVED·FALSE_POSITIVE 는 터미널)
```

- 전이는 **`Incident.transitionTo`**(`canTransitionTo`) 로만 — 위반 시 `InvalidIncidentTransitionException`(웹 **409**). status 직접 세팅 금지.
- `acknowledge`/`resolve`(수동)/`autoResolve`(actor="alertmanager", OPEN·ACK 어디서든)/`markFalsePositive`.
  **FALSE_POSITIVE 는 RESOLVED 와 별도 보존**(재발/오탐 통계 분리).
- **터미널은 reopen 없음** — 해제된 알람 재발화는 **새 인시던트**(활성 유일성 인덱스가 자연 허용).
- `IncidentSeverity`: INFO(1)<WARNING(2)<CRITICAL(3). **refire 는 상향(승격)만** 반영(하향 강등 금지). `fromLabel` 미상 → WARNING(보수적).
- enum: `IncidentSource`(ALERTMANAGER/ANOMALY/MANUAL), `SignalCategory`(11종, 미매핑→UNKNOWN+경고), `TimelineEventType`(7종, DB CHECK 와 1:1).

### 중복 차단 · refire · 동시성

- **활성 유일성**: `uq_incident_active ON incidents(source, correlation_key) WHERE status IN ('OPEN','ACKNOWLEDGED')`.
  **★ `IncidentStatus.isActive()` 집합과 이 인덱스 WHERE 절은 항상 일치해야 한다**(한쪽만 바뀌면 유일성 붕괴). correlation_key=Alertmanager fingerprint.
- `refire(severity, now, suppression)`: `requireActive()`, lastSeenAt·occurrenceCount++, 승격 시 즉시 타임라인 기록,
  일반 refire 는 `suppression`(기본 30분) 경과 시에만 REFIRED 기록(repeat_interval 폭주 방지).
- 낙관적 락(`@Version`) + **alert 1건=1 트랜잭션**: `AlertApplier.apply` 가 독립 `@Transactional`(자기호출 프록시 미적용이라 **별도 빈 필수**).
  `applyWithConflictRetry`: DataIntegrityViolation|OptimisticLocking catch → 새 tx 재시도 `MAX_ATTEMPTS=5`, refire 로 수렴. 한 건 실패가 배치 안 막음.

## Alertmanager webhook

- `POST /api/ops/webhook/alertmanager`. Alertmanager 는 커스텀 헤더 불가 → **`Authorization: Bearer <INTERNAL_API_KEY 재사용>`**(`OpsWebhookAuthFilter`,
  `MessageDigest.isEqual` 상수시간 비교). 미설정 시 운영 fail-closed 401 / 개발 통과+경고.
- **★ webhook 응답은 항상 200** — 5xx 면 Alertmanager 가 그룹 전체 재시도 폭주. 부분 실패는 로그·집계로만, 유실은 repeat_interval 이 보상. fingerprint 없는 alert 는 필터링.

## signal BC — 신호 버킷 (failure_rate)

- `MetricBucket`(record): 한 구조로 두 유형 — 카운터형(`countTotal`=시도/분모, `countSignal`=실패/분자, `failureRate()=signal/total`,
  분모 0 이면 0.0) + 게이지형(valueSum/max/sampleCount, `average()`). **계산은 읽기 시점**, 적재는 UPSERT 누적.
- `BucketWindow.floor(instant, bucketSeconds)` — UTC epoch초 내림 정렬. `ops_metric_bucket` PK `(metric_key, bucket_start)`,
  **5분(300초) ON CONFLICT UPSERT**(동시 다중 컨슈머/폴러도 원자 누적). 네이티브 쿼리는 스키마(opslab) 직접 명시.
- 분모(2a): `DomainEventSignalConsumer` 가 성공 이벤트(order.created/payment.captured/settlement.created)를 그룹 `lemuel-operation` **구독만**(신규 발행 0),
  `count_total+1`. Prometheus 폴링 게이지(`MetricPollingService`, 개별 try 격리).
- 분자(2b): `OpsFailureSignalConsumer` 가 `lemuel.ops.*.failed` 구독 → `count_total`도 함께 +1(시도)이라 failure_rate=signal/total 성립.
- **신호 컨슈머는 processed_events 멱등 미적용**(의도) — 고volume 통계라 멱등 행 무한팽창, at-least-once 중복은 5분 버킷에 무해. 적재 실패해도 **ack**(컨슈머 정지 방지).

## ★ opssignal 원칙 (shared-common `common.opssignal`) — 정산 이벤트와 정반대

전 서비스가 실패 지점에서 `OpsSignalPort.emit(...)` 호출. `OpsSignalCategory`: ORDER/PAYMENT/STOCK/SHIPPING/SETTLEMENT (`lemuel.ops.*.failed`).

1. **절대 예외를 던지지 않는다** — 관측 신호가 결제/정산 비즈니스 경로를 깨선 안 됨. 직렬화·조립 예외도 삼키고 로그만.
2. **Outbox 미사용** — 실패는 흔히 tx 롤백을 동반하므로 outbox 행도 사라진다. **out-of-band 직접 Kafka** 라야 롤백돼도 관측됨.
   → **정산/투자 도메인의 Outbox 패턴과 결정적으로 다르다**(idempotency-and-events 는 도메인 이벤트용, opssignal 은 예외).
3. **비동기 fire-and-forget** — `send().get()` 없음. 버퍼에 넣고 즉시 반환, 실패는 콜백 로그만.
- 배선: PayoutSingleExecutor(settlement.failed)·RefundLifecycle(payment.failed)·재고차감(stock.depleted)·ShippingDelayScanner(shipping.delayed).
  Kafka 없으면 `NoOpOpsSignalPublisher`.

## 안티패턴 (발견 시 지적)

- **opssignal 이 throw** — 호출자 catch 블록을 2차 예외로 오염. / **opssignal 에 Outbox 사용**(롤백 시 신호 유실). / **동기 발행(.get())**.
- `Incident.transitionTo` 우회 status 세팅 / 터미널 reopen / severity 하향 강등.
- `isActive()` 집합과 `uq_incident_active` WHERE 절 불일치.
- 신호 컨슈머에 processed_events 멱등 행 쌓기 / 적재 실패 시 ack 보류·무한재시도.
- `AlertApplier` 자기호출(프록시 미적용) / webhook 5xx 반환 / category-mapping 하드코딩(yml 외부화, 미매핑 UNKNOWN).
- Prometheus 한 쿼리·한 alert 실패로 배치 전체 중단.
