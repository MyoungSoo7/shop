# Runbook — Notification DLT (알림 격리)

> **2026-08-25(ADR 0041)**: 알림은 폴리글랏 `notification-service`(8130)에서
> `operation-service`(8092)의 `notification` 슬라이스로 흡수됐다. 컨테이너는 `lemuel-operation`,
> 메트릭은 `operation_kafka_dlt_published_total` 이다. 컨슈머 그룹 이름(`notification-service`)은
> 오프셋 승계를 위해 그대로다 — `rpk group describe notification-service` 는 여전히 동작한다.

**연결 알림:** `NotificationKafkaDltPublishedSpike` (critical)
**담당:** 백엔드 온콜

## 왜 별도 러너북인가

settlement 의 DLT 는 `/admin/dlq` 콘솔(`DlqAdminController` + `DlqReplayService`, ADR 0017)로 조회·재처리한다.
**알림 슬라이스에는 그 경로가 없다.** 이 슬라이스는 자체 저장소가 없는 무영속이라
격리 이력을 저장할 곳도, 재처리 상태를 관리할 곳도 없다. 그래서 replay 는 **브로커를 직접 다루는 수작업**이며,
그 절차를 여기 고정한다. 알림이 울렸는데 절차가 없으면 격리는 유실과 실질적으로 같다.

## 증상

`rate(notification_kafka_dlt_published_total{job="notification"}[5m]) > 0.01` 이 2분 지속.
= 5분 평균 3건 이상이 `<원본토픽>.DLT` 로 격리되는 중. 정상 운영에서는 0 이어야 한다.

## 1. 무엇이 격리됐는지 본다

```bash
# 대상 토픽: lemuel.settlement.confirmed / lemuel.payment.captured / .refunded /
#            lemuel.payment.confirmed / lemuel.investment.executed 의 .DLT
docker exec lemuel-redpanda rpk topic consume lemuel.settlement.confirmed.DLT --num 20 --format json \
  | jq '{key, value: .value[0:200], headers}'
```

`kafka_dlt-exception-fqcn` 헤더가 원인을 가른다.

| 헤더 값                                | 의미                                                   | 조치 분기                    |
| -------------------------------------- | ------------------------------------------------------ | ---------------------------- |
| `...UnparseableEventPayloadException`   | 계약 드리프트 — 페이로드가 JSON 으로 파싱되지 않음     | 2-A                          |
| `...NotificationDispatchFailedException` | 활성 채널 전멸 — 알림이 아무 데도 도달하지 않음        | 2-B                          |
| 그 외(임의 예외)                        | 템플릿·dedupe 등 예기치 못한 실패. 재시도 3회를 소진함 | 2-B 준용 + 스택트레이스 확인 |

> 헤더 전체(`kafka_dlt-original-topic`·`-partition`·`-offset`·`-exception-stacktrace`)는
> `DeadLetterPublishingRecoverer` 가 자동으로 붙인다. 원본 `event_id`·`traceparent` 는 패스스루된다.

## 2. 원인별 조치

### 2-A. 계약 드리프트 (`UnparseableEventPayloadException`)

프로듀서가 계약을 어긴 것이다. **replay 하지 마라** — 같은 바이트는 다시 파싱 실패한다.

1. `kafka_dlt-original-topic` 으로 프로듀서 서비스를 특정한다(토픽 로스터: `../../../SPEC.md` §5).
2. 계약 스키마와 대조: `shared-common/src/testFixtures/resources/contracts/events/<토픽>.schema.json`
3. 프로듀서를 고치고 **양방향 계약 테스트**를 추가한다(→ `event-contract-change` 스킬).
   계약 테스트가 있었다면 빌드 시점에 걸렸을 사고다.

### 2-B. 채널 전멸 (`NotificationDispatchFailedException`)

메시지는 정상이고 발송 채널이 죽어 있었다. **채널 복구가 먼저다.**

1. 어떤 채널이 실패했는지는 예외 메시지에 들어 있다:
   `all channels failed on topic=... eventId=... — email(3 attempts): smtp down, sse(3 attempts): ...`
2. 채널(SMTP/Slack/SSE 구독자)을 복구한다. 복구 전 replay 는 다시 DLT 로 돌아온다.
3. 복구를 확인한 뒤 3단계로 간다.

## 3. Replay (원본 토픽 재투입)

**dedupe TTL 을 반드시 먼저 확인한다.** `DedupeStore` 는 in-memory + TTL 기본 30분(`app.dedupe.ttl-minutes`)이며
`dispatch` **직전에** eventId 를 선점한다. 즉 격리 후 30분이 지나기 전에 replay 하면 dedupe 스킵으로
**조용히 no-op** 이 된다(로그: `dedupe skip eventId=...`). 프로세스를 재시작했다면 저장소가 비어 즉시 재발송된다.

```bash
# 1) DLT 에서 원본 페이로드와 event_id 헤더를 꺼낸다
docker exec lemuel-redpanda rpk topic consume lemuel.settlement.confirmed.DLT --num 1 --format json > /tmp/dlt.json
KEY=$(jq -r '.key' /tmp/dlt.json)
EVENT_ID=$(jq -r '.headers[] | select(.key=="event_id") | .value' /tmp/dlt.json)
jq -r '.value' /tmp/dlt.json > /tmp/payload.json

# 2) 원본 토픽으로 되돌린다 — -z none 필수(이미지에 snappy 네이티브가 없다)
docker exec -i lemuel-redpanda rpk topic produce lemuel.settlement.confirmed \
  -k "$KEY" -H "event_id:$EVENT_ID" -z none < /tmp/payload.json
```

- **`event_id` 를 반드시 실어라.** 빠뜨리면 컨슈머가 key 를 폴백으로 쓰는데, key 는 애그리거트 ID 라
  같은 애그리거트의 다른 이벤트와 충돌해 잘못된 dedupe 를 만든다.
- **`kafka_dlt-*` 헤더는 싣지 마라.** 진단용이며 재투입 시 노이즈다.
- 여러 건이면 한 건씩 확인하며 진행한다 — 이 서비스에는 replay 횟수 제한(settlement 의 `x-replay-count`)이 없다.

## 4. 복구 확인

```bash
# DLT 유입이 멎었는지
curl -s http://localhost:8092/actuator/prometheus | grep operation_kafka_dlt_published_total
# 재발송이 실제로 일어났는지 (dedupe 스킵이면 이 로그가 없다)
docker logs lemuel-operation --since 5m | grep -E "dispatched|dedupe skip"
```

## 5. 재발 방지

- 2-A 가 반복되면 계약 테스트가 비어 있다는 뜻이다 — 토픽에 스키마·샘플·양방향 테스트를 채운다(ADR 0024).
- 2-B 가 반복되면 채널 회복탄력성 문제다. 채널별 타임아웃·재시도는
  `NotificationDispatcher`(per-channel timeout + 3회 백오프)에 있고, 그걸 넘긴 실패만 DLT 로 온다.
- **부분 성공은 DLT 로 오지 않는다**(한 채널이라도 성공하면 격리하지 않는다 — replay 중복 발송 방지).
  즉 "일부 채널만 죽은" 장애는 이 알림이 아니라 `dispatched ... failure=N` 로그로 봐야 한다.

## 알려진 한계 (설계상 수용)

| 한계                  | 이유                                                   |
| --------------------- | ------------------------------------------------------ |
| replay 자동 경로 없음 | 무영속 standalone — 상태를 둘 DB 가 없다               |
| 격리 이력 조회 API 없음 | 위와 동일. 브로커가 유일한 저장소다                    |
| dedupe 가 휘발성      | MVP. 프로세스 재시작 시 중복 발송 가능(at-least-once)  |

영속 저장소를 붙이는 순간 settlement 와 동형의 `/admin/dlq` 를 만드는 것이 옳다 — 그때 이 러너북은 폐기한다.
