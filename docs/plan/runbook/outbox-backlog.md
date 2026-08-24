# Runbook — Outbox PENDING 적체

**연결 알림:** `OutboxPendingBacklog` (warning), `OutboxPendingCritical` (critical), `OutboxPublishSlow` (warning)

## 증상

`outbox_pending_count` 메트릭이 임계값 초과.
Kafka 컨슈머 혹은 다운스트림 서비스로 이벤트가 전달되지 않거나 지연.

## 1. 현황 파악

```sql
-- PENDING 건수 및 오래된 건
SELECT status, count(*), min(created_at), max(created_at)
FROM opslab.outbox_events
WHERE status IN ('PENDING', 'FAILED')
GROUP BY status;

-- 실패 원인 분포
SELECT last_error, count(*)
FROM opslab.outbox_events
WHERE status = 'FAILED'
GROUP BY last_error
ORDER BY 2 DESC LIMIT 10;
```

## 2. 원인별 대응

### (A) OutboxPublisherScheduler 가 돌지 않음

- `/actuator/scheduledtasks` 에서 스케줄러 등록 여부 확인.
- 최근 배포 시 `@Scheduled` 빈이 제거/조건부 비활성 되었는지 코드 리뷰.
- 복구: 애플리케이션 재기동.

### (B) Kafka/Redpanda 브로커 장애

```bash
# 브로커 상태
kafka-broker-api-versions.sh --bootstrap-server $KAFKA_BROKER

# 토픽 목록
kafka-topics.sh --list --bootstrap-server $KAFKA_BROKER | grep lemuel
```

- 복구 후 outbox 폴러가 자동 재개 (FAILED 는 retry 대상).

### (C) 특정 이벤트가 반복 실패 (Poison Pill)

- `last_error` 로 원인 파악.
- 해당 이벤트 `status = 'PUBLISHED'` 로 수동 마킹 (건너뛰기) OR payload 수정 후 `retry_count=0` 초기화.
  ```sql
  UPDATE opslab.outbox_events
  SET status='PENDING', retry_count=0, last_error=NULL
  WHERE id IN (?);
  ```

### (D) 다운스트림 컨슈머 lag

```bash
kafka-consumer-groups.sh --bootstrap-server $KAFKA_BROKER \
  --describe --group settlement-consumer
```

- 컨슈머 scale-out 또는 파티션 재분배.

## 3. 심각한 적체 (>10k)

- 이벤트 유실 위험 단계. `OutboxPendingCritical` 발동.
- `#alerts-critical` 공지 + 즉시 온콜 소집.
- 브로커 복구 후 대량 폴링으로 DB·Kafka 양쪽 부하 → 폴러 `polling-delay-ms` 를 일시적으로 감소시키지 말 것.

## 4. 사후

- 대사 `outbox_published_equals_settlements_created` 가 복구됐는지 `/api/reports/cashflow` 로 재확인 (참고: 해당 기간 `from/to` 로 조회).
- 포스트모템 + Alertmanager 룰 튜닝 검토.
