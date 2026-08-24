# operation-service 기능명세서 (Function Specification)

> 운영 관제 마이크로서비스 — Alertmanager 알람을 인시던트로 흡수·관리하고, 도메인/인프라 신호를
> 5분 시계열 버킷에 집계해 이상 탐지의 기반을 마련한다.
>
> - **포트**: 앱 `8092` / 관리(actuator) `8093` / 컨테이너 내부 `8080`
> - **DB**: 자체 `lemuel_operation` (DB-per-service, 스키마명은 `opslab` 재사용 — loan 과 동일 이유)
> - **의존 경계**: `shared-common:1.0.0` 만 의존. order/settlement/loan import 0 (연계는 Kafka 이벤트로만)
> - **아키텍처**: 헥사고날 (Ports & Adapters), 도메인 순수 POJO
> - **로드맵 진행**: Phase 1(인시던트) ✅ · Phase 2a(신호 분모+인프라 게이지) ✅ · Phase 2b(실패 분자) ✅ · Phase 3(이상 탐지)·Phase 4(AI 브리핑) 예정
> - **상세 설계**: `../docs/etc/design-pattern/design/operation-service-phase1.md`(로컬 전용, 저장소 미포함)

---

## 1. 서비스 개요

operation-service 는 Lemuel 플랫폼의 **운영 관제(observability & incident)** 컨텍스트를 담당한다.
두 개의 Bounded Context 로 구성된다.

| BC | 책임 | 상태 |
|----|------|------|
| **incident** | Alertmanager 알람 → 인시던트 라이프사이클(OPEN→ACK→RESOLVED/FALSE_POSITIVE) 적재·관리 | Phase 1 |
| **signal** | 도메인 이벤트(분모)·실패 이벤트(분자)·Prometheus 인프라 게이지를 5분 버킷에 집계 | Phase 2a/2b |

핵심 설계 원칙:

- **비즈니스 트랜잭션 무영향**: operation 은 관측만 한다. 실패 신호 발행(shared-common `common.opssignal`)은
  **절대 throw 하지 않고 Outbox 도 쓰지 않는다**(실패는 롤백을 동반하므로 out-of-band 직접 발행).
- **관제의 관제**: operation-service 자신이 죽으면 Prometheus 알람(`OperationServiceDown`)이 감시한다.
- **결정론 우선**: 모든 수치 계산은 SQL/Java 로 하고, AI(Phase 4)는 계산된 결과의 설명만 담당한다.

---

## 2. 도메인 모델

### 2.1 Incident (애그리게잇 루트)

알람/이상 신호의 라이프사이클 컨테이너. 순수 POJO 로 상태 전이를 자체 강제한다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long | PK |
| `correlationKey` | String | Alertmanager fingerprint (source별 유일 식별자) |
| `source` | IncidentSource | ALERTMANAGER / ANOMALY / MANUAL |
| `category` | SignalCategory | 신호 분류 (11종, §2.4) |
| `severity` | IncidentSeverity | CRITICAL / WARNING / INFO (refire 시 **상향만** 반영) |
| `status` | IncidentStatus | OPEN / ACKNOWLEDGED / RESOLVED / FALSE_POSITIVE |
| `title` | String | alertname |
| `description` | String | annotations 요약 |
| `service` | String | labels.component 원본 |
| `labels`, `annotations` | Map | 원본 라벨/주석 (runbook_url 포함) |
| `firstSeenAt` | Instant | 최초 발생(alert startsAt) |
| `lastSeenAt` | Instant | 마지막 firing 수신 시각 |
| `occurrenceCount` | int | firing 반복 수신 횟수 |
| `lastRefireLoggedAt` | Instant | REFIRED 타임라인 억제 판정용 |
| `acknowledgedAt/By`, `resolvedAt/By` | Instant/String | 처리 이력 (`resolvedBy='alertmanager'` = 자동 해제) |
| `version` | long | 낙관적 락 (@Version) — 운영자 조작 경쟁 방지 |

### 2.2 IncidentStatus 상태머신

```
OPEN ──────────→ ACKNOWLEDGED ──→ RESOLVED
  │                   │
  ├──→ RESOLVED       └──→ FALSE_POSITIVE
  └──→ FALSE_POSITIVE
```

- **터미널**: `RESOLVED`, `FALSE_POSITIVE` (재전이 불가).
- 전이는 `IncidentStatus.canTransitionTo()` 가 강제 — 위반 시 `InvalidIncidentTransitionException`(→ 409).
- **활성(active)** = OPEN 또는 ACKNOWLEDGED. `uq_incident_active` partial unique index 의 상태 집합과 일치.
- **reopen 없음**: 해제된 알람이 재발화하면 partial unique index 가 비어 있으므로 **새 인시던트**가 생성된다.
  재발 이력은 `correlationKey` 로 묶어 조회한다(flapping 알람에서 reopen 상태머신은 복잡도만 키움 → 단순성 우선).

### 2.3 IncidentTimelineEntry (타임라인 VO)

인시던트별 이벤트 이력. 사실상 감사 로그를 겸한다.

`eventType`: `OPENED / REFIRED / ACKNOWLEDGED / RESOLVED / AUTO_RESOLVED / FALSE_POSITIVE / COMMENT`
`actor`: 운영자 username 또는 `alertmanager`

### 2.4 SignalCategory (11종) — 신호 분류

| enum | 의미 | 유입 경로(component 라벨) |
|------|------|--------------------------|
| `ORDER_FAILURE` | 주문 실패 | (Phase 2b 이벤트) |
| `PAYMENT_FAILURE` | 결제/환불 실패 | `refund` |
| `STOCK_SHORTAGE` | 재고 부족 | (Phase 2b 이벤트) |
| `SHIPPING_DELAY` | 배송 지연 | (Phase 2b 이벤트) |
| `SETTLEMENT_FAILURE` | 정산 실패 | `settlement-batch`, `settlement-adjustment`, `cashflow-report` |
| `KAFKA_BACKLOG` | Kafka 적체 | `kafka-consumer`, `outbox`, `settlement-projection` |
| `REDIS_FAILURE` | Redis 장애 | (Phase 2 exporter) |
| `DB_DEADLOCK` | DB 데드락/커넥션 | `database` |
| `API_TIMEOUT` | API 지연/에러율 | `http` |
| `INFRA_ETC` | 기타 인프라 | `jvm`, `application` |
| `UNKNOWN` | 매핑 실패 | 매핑 누락 라벨 (경고 로그) |

> 매핑은 `application.yml`(`app.ops.category-mapping`)로 외부화 — 알람 룰이 늘면 재빌드 없이 재기동만으로 대응.

### 2.5 MetricBucket (signal BC 읽기 모델)

`ops_metric_bucket` 한 행. 두 신호 유형을 통합:

- **카운터형**(Kafka 이벤트): `countTotal`(시도=분모) / `countSignal`(실패=분자) → `failureRate() = signal/total`
- **게이지형**(Prometheus 폴링): `valueSum` / `valueMax` / `sampleCount` → `average() = sum/count`

분모 0 이면 `failureRate()`=0.0 (최소표본 게이트로 판정 제외).

---

## 3. 기능 명세

### F-1. Alertmanager Webhook 수신 → 인시던트 반영

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `POST /api/ops/webhook/alertmanager` |
| 인증 | `Authorization: Bearer <INTERNAL_API_KEY>` (`OpsWebhookAuthFilter`) — 미설정 시 개발 모드 통과+경고 |
| 입력 | Alertmanager webhook v4 페이로드 (`alerts[]`) |
| 처리 단위 | 그룹이 아닌 **개별 alert** — `fingerprint` = correlationKey |
| 응답 | **항상 200** + `{ received, applied, failed }` |

**처리 로직** (`IngestAlertService` / `AlertApplier`):

- **firing + 활성 없음** → 신규 INSERT (OPEN), category=매핑(component), severity=labels.severity → timeline `OPENED`
- **firing + 활성 있음** → `lastSeenAt`/`occurrenceCount++` 갱신, severity **상향만** 반영(승격 시 timeline)
  → timeline `REFIRED` (**직전 REFIRED 로부터 30분 경과 시에만** 기록 — repeat_interval 폭주 방지)
- **resolved + 활성 있음** → RESOLVED 전이, `resolvedBy='alertmanager'`, `resolvedAt=endsAt` → timeline `AUTO_RESOLVED`
- **resolved + 활성 없음** → no-op (이미 닫힘/유실 — DEBUG 로그)

**멱등성 / 동시성**:

- alert 1건 = 1 독립 트랜잭션(`AlertApplier`). 한 건 실패가 배치 전체를 막지 않음(per-alert try-catch, 실패 건수만 집계).
- repeat_interval 재전송 → refire 경로가 자연 멱등(occurrenceCount 만 증가).
- 동시 webhook 경쟁 → 이중 INSERT(`DataIntegrityViolation`) / 겹친 refire(`OptimisticLockingFailure`)를
  catch 하고 **새 트랜잭션으로 최대 5회 재시도** → 매 시도가 최신 상태 재조회 → refire 로 수렴.
- 200 고정 이유: 5xx 응답 시 Alertmanager 가 그룹 전체를 재시도해 폭주 → 유실은 repeat_interval 재전송이 보상.

### F-2. 인시던트 목록 조회

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `GET /api/ops/incidents` |
| 인증 | JWT `ROLE_ADMIN` |
| 필터 | `status?`, `category?`, `severity?`, `from?`, `to?`(firstSeenAt 기준 ISO-8601) |
| 페이징 | `page=0`, `size=20`(최대 100), 정렬 고정 `lastSeenAt DESC` |
| 구현 | 동적 조건은 JPA **Specification** 조립 (JPQL `:param IS NULL OR` 는 PG bytea 오류 회피) |

응답: `PageResponse<IncidentResponse>` (`content[]` + `page/size/totalElements/totalPages`).
요약 필드 — id, correlationKey, source, category, severity, status, title, service, firstSeenAt, lastSeenAt, occurrenceCount, acknowledgedBy, resolvedBy.

### F-3. 인시던트 단건 상세 조회

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `GET /api/ops/incidents/{id}` |
| 인증 | JWT `ROLE_ADMIN` |
| 미존재 | 404 (`IncidentNotFoundException`) |

응답: 목록 필드 + `description`, `labels`, `annotations`(runbook_url), `timeline[]`(전 이력).

### F-4. 인시던트 상태 전이 (ack / resolve / false-positive)

| 엔드포인트 | 동작 | 결과 상태 |
|-----------|------|----------|
| `POST /api/ops/incidents/{id}/ack` | 운영자 확인 | ACKNOWLEDGED |
| `POST /api/ops/incidents/{id}/resolve` | 운영자 수동 해제 | RESOLVED |
| `POST /api/ops/incidents/{id}/false-positive` | 오탐 처리 | FALSE_POSITIVE |

- 요청 바디(선택): `{ "note": "메모" }`
- `actor` = JWT principal (`authentication.getName()`)
- 전이 불가(`canTransitionTo` 실패) 또는 낙관적 락 충돌 → **409 Conflict** (`OpsWebExceptionHandler`)
- 미존재 → 404. 응답 = 갱신된 상세(F-3 형식)
- FALSE_POSITIVE 는 RESOLVED 와 구분 보존 → 재발 통계에서 제외 가능

### F-5. 인시던트 코멘트 추가

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `POST /api/ops/incidents/{id}/comments` |
| 입력 | `{ "note": "..." }` (필수) |
| 동작 | timeline `COMMENT` 항목 추가 (상태 전이 없음) |
| 응답 | 갱신된 상세(F-3) |

### F-6. 대시보드 요약 통계

| 항목 | 내용 |
|------|------|
| 엔드포인트 | `GET /api/ops/incidents/summary?window=24h` |
| window | `1h` / `24h`(기본) / `7d` — 형식 오류 시 400 |

응답:

```json
{
  "window": "24h",
  "openTotal": 3,
  "byStatus":   { "OPEN": 3, "ACKNOWLEDGED": 1, "RESOLVED": 12, "FALSE_POSITIVE": 2 },
  "byCategory": { "SETTLEMENT_FAILURE": 2, "KAFKA_BACKLOG": 5, "API_TIMEOUT": 1 },
  "bySeverity": { "CRITICAL": 1, "WARNING": 6, "INFO": 0 },
  "mttrSeconds": 1840
}
```

`mttrSeconds` = window 내 RESOLVED 건의 `avg(resolvedAt - firstSeenAt)` (Phase 1 유일한 운영 품질 지표).

### F-7. 신호 분모 집계 — 도메인 성공 이벤트 구독 (Phase 2a)

| 항목 | 내용 |
|------|------|
| 컴포넌트 | `DomainEventSignalConsumer` (`@ConditionalOnProperty app.kafka.enabled=true`) |
| 구독 토픽 | `lemuel.order.created` / `lemuel.payment.captured` / `lemuel.settlement.created` |
| 컨슈머 그룹 | `lemuel-operation` (독립 그룹 → settlement/loan 소비 무영향) |
| 동작 | `recordEvent(metricKey, signal=false, ts)` → `count_total`(분모) +1 |
| 버킷 시각 | Kafka record timestamp (페이로드 파싱 없음 — 스키마 변화에 견고) |
| offset | `auto-offset-reset=latest` (과거 재적재 불필요) |

- 멱등 미적용(통계 5분 버킷은 at-least-once 에 강건, 고volume 이벤트마다 멱등 행 쌓으면 무한 팽창).
- 적재 실패해도 `ack` 진행 → 컨슈머 정지 방지(통계 손실만 감수).

### F-8. 신호 분자 집계 — 실패 이벤트 구독 (Phase 2b)

| 항목 | 내용 |
|------|------|
| 컴포넌트 | `OpsFailureSignalConsumer` (`app.kafka.enabled=true`) |
| 구독 토픽 | `lemuel.ops.{order,payment,stock,shipping,settlement}.failed` |
| 동작 | `recordEvent(metricKey, signal=true, occurredAt)` → `count_signal`+1 **&** `count_total`+1 |
| 버킷 시각 | envelope `occurredAt` 우선, 부재/파싱 실패 시 record timestamp 폴백 |

→ `failure_rate = count_signal / count_total` 성립 (성공 분모는 F-7).

**발행 backbone** (shared-common `common.opssignal`, 각 서비스가 실패 지점에서 호출):

| 신호 | 발행 지점 |
|------|----------|
| `settlement.failed` | `PayoutSingleExecutor` — 펌뱅킹 실패 catch |
| `payment.failed` | `RefundLifecycle.fail` — 환불 실패 기록 직후 |
| `stock.depleted` | `Decrease{Variant,Product}StockService` — 재고 부족 분기 |
| `shipping.delayed` | `ShippingDelayScanner` 배치 — IN_TRANSIT 72h crossing window |
| `order.failed` | **미배선**(문서화) — 단일 실패 catch 지점 부재, 소비자·토픽·envelope 는 준비 완료 |

> 발행 계약: `OpsSignalPort` 는 **절대 throw 안 함** + Outbox 미사용(실패 롤백돼도 관측되도록 out-of-band 직접 발행) + 비동기 fire-and-forget(2차 예외 오염 방지). PII 미포함(ID·비식별 메타만).

### F-9. 인프라 게이지 폴링 — Prometheus 인스턴트 쿼리 (Phase 2a)

| 항목 | 내용 |
|------|------|
| 컴포넌트 | `MetricPollingScheduler`(@Scheduled fixedDelay) → `MetricPollingService` → `PrometheusMetricSourceAdapter` |
| 토글 | `app.ops.prometheus.enabled=true` (기본 off → `NoOpMetricSourceAdapter`, 스케줄러 미기동) |
| 호출 | RestClient `GET {base-url}/api/v1/query` (Boot4 는 `RestClient.builder()` 직접 조립) |
| 적재 | 결과 벡터 첫 샘플 → 게이지 버킷(`value_sum`/`value_max`/`sample_count`) |
| 격리 | 한 쿼리 실패가 나머지 미차단, 결과 부재·NaN/Inf 는 empty 로 정규화해 건너뜀 |

기본 PromQL (`app.ops.prometheus.queries`, yml 외부화):

| metric_key | PromQL |
|-----------|--------|
| `kafka.lag.max` | `max(kafka_consumergroup_lag)` |
| `redis.up` | `min(redis_up)` |
| `db.deadlock.rate` | `sum(rate(pg_stat_database_deadlocks[5m]))` |
| `http.error.ratio` | `100 * sum(rate(http_..._count{status=~"4..\|5.."}[5m])) / sum(rate(http_..._count[5m]))` |

### F-10. 신호 버킷 원자 UPSERT (Phase 2a)

| 항목 | 내용 |
|------|------|
| 컴포넌트 | `SignalRecordingService` / `MetricBucketPersistenceAdapter` |
| 저장 | 네이티브 `INSERT ... ON CONFLICT (metric_key, bucket_start) DO UPDATE` (원자 누적) |
| 정렬 | `bucketStart` = 300초(5분) 정렬 (UTC) — Phase 3 z-score 판정 단위와 일치 |
| 동시성 | 다중 컨슈머/폴러가 같은 버킷에 몰려도 **앱 락 없이** 누적 |

---

## 4. API 요약

베이스: `/api/ops` · 인증: JWT `ROLE_ADMIN` (webhook 만 Bearer 예외)

| Method | Path | 기능 | 인증 |
|--------|------|------|------|
| POST | `/api/ops/webhook/alertmanager` | Alertmanager 수신 | Bearer internal key |
| GET | `/api/ops/incidents` | 목록(필터+페이징) | ADMIN |
| GET | `/api/ops/incidents/{id}` | 단건 + 타임라인 | ADMIN |
| GET | `/api/ops/incidents/summary` | 대시보드 요약 | ADMIN |
| POST | `/api/ops/incidents/{id}/ack` | 확인 처리 | ADMIN |
| POST | `/api/ops/incidents/{id}/resolve` | 수동 해제 | ADMIN |
| POST | `/api/ops/incidents/{id}/false-positive` | 오탐 처리 | ADMIN |
| POST | `/api/ops/incidents/{id}/comments` | 코멘트 추가 | ADMIN |

**공통 에러**: 400(window/입력 형식) · 401(webhook Bearer 실패) · 403(비-ADMIN) · 404(미존재) · 409(전이 불가/락 충돌).

---

## 5. 데이터 모델 (Flyway)

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | `V1__incident_core.sql` | `incidents` + `incident_timeline` (+ `uq_incident_active` partial unique) |
| V2 | `V2__outbox_processed_events.sql` | Outbox/멱등 인프라(루트 스캔 정합용) |
| V3 | `V3__audit_logs.sql` | `audit_logs` (shared-common `AuditLogJpaEntity` ddl `validate` 정합) |
| V4 | `V4__signal_metric_bucket.sql` | `ops_metric_bucket` (5분 신호 버킷) |

**핵심 불변식**: `uq_incident_active ON incidents(source, correlation_key) WHERE status IN ('OPEN','ACKNOWLEDGED')`
→ 같은 correlationKey 의 활성 인시던트 최대 1건 (동시 webhook 경쟁의 최종 방어선).

---

## 6. 보안

| 경로 | 정책 |
|------|------|
| `/api/ops/webhook/**` | permitAll (보안 체인) → `OpsWebhookAuthFilter` 가 `Bearer INTERNAL_API_KEY` 검증 |
| `/api/ops/**` (그 외) | JWT `ROLE_ADMIN` 전용 (`JwtAuthenticationFilter`) |
| 그 외(actuator/swagger) | shared-common 전역 체인 |

- 운영 콘솔 체인은 `@Order(1)` + `securityMatcher("/api/ops/**")` 로 전역 체인보다 앞에 배치.
- webhook 인증은 시크릿 값을 기존 `INTERNAL_API_KEY` 재사용(Alertmanager 는 커스텀 헤더 미지원 → Bearer 사용).
- 필터 경로 매칭은 `getRequestURI()` 기준(MockMvc 등 servletPath 빈 문자열 환경 호환).

---

## 7. 인프라 연동

| 대상 | 변경 |
|------|------|
| docker-compose | `operation-postgres`(127.0.0.1:5439) + `operation-service`(8092) + `postgres-exporter`(9187) + `redis-exporter`(9121) |
| gateway-service | `Path=/api/ops/**` → `operation-service:8080` 라우트 |
| alertmanager.yml | `operation-webhook` receiver(Bearer, `send_resolved: true`) + 최상단 route(`continue: true`) |
| prometheus.yml | `operation`/`postgres-exporter`/`redis-exporter` scrape job |
| alert-rules.yml | `OperationServiceDown`(`up{job="operation"}==0`, 1m) — 관제 시스템 자기 감시 |

토글 환경변수: `APP_KAFKA_ENABLED` · `OPS_PROMETHEUS_ENABLED` · `OPS_PROMETHEUS_URL` · `INTERNAL_API_KEY` · `JWT_SECRET`.

---

## 8. 테스트 커버리지

| 레벨 | 대상 | 핵심 케이스 |
|------|------|-------------|
| 도메인 | `IncidentTest`, `MetricBucketTest`, `BucketWindowTest` | 상태머신 전 조합, 터미널 차단, severity 승격, failure_rate/average |
| 서비스 | `IngestAlertServiceTest`, `AlertApplierTest`, `IncidentCommandServiceTest`, `SignalRecordingServiceTest`, `MetricPollingServiceTest` | firing 신규/refire/resolved no-op, category 매핑, REFIRED 30분 억제, 재시도 |
| 어댑터 | `DomainEventSignalConsumerTest`, `OpsFailureSignalConsumerTest`, `PrometheusMetricSourceAdapterTest` | 분모/분자 적재, occurredAt 폴백, Prometheus 파싱/격리 |
| 통합 | `IncidentLifecycleIntegrationTest`, `MetricBucketUpsertIntegrationTest` (Testcontainers PG) | webhook→ack→resolved 전체 흐름, 동시 경쟁 1건 수렴, UPSERT 누적 |
| 아키텍처 | `OperationArchitectureTest` (ArchUnit 1.4.1) | domain→adapter 의존 0, port 방향 검증 |

---

## 9. 로드맵 (남은 Phase)

- **Phase 3 — 베이스라인 이상 탐지**: `ops_metric_bucket` 의 요일·시간대 베이스라인 + z-score 로
  "평소 대비 N% 증가" 판정 → `IncidentSource.ANOMALY` 인시던트 자동 생성(같은 상태머신 재사용).
- **Phase 4 — AI 브리핑**: summary/이상 탐지 결과를 재료로 일일 운영 브리핑 생성(계산은 결정론, AI 는 설명만).
- **선점 완료**: `IncidentSource.ANOMALY/MANUAL`, `SignalCategory` 11종 전체, summary `window` 파라미터 —
  Phase 3/4 가 코드 변경 없이 붙는다.
```