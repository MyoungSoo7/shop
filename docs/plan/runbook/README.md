# Runbook 인덱스

운영 중 발생 가능한 시나리오와 대응 절차.

| 시나리오 | 런북 | 연결 알림 |
|---------|------|-----------|
| Toss PG 장애 | [toss-pg-outage.md](toss-pg-outage.md) | `TossPgCircuitOpen` |
| Outbox PENDING 적체 | [outbox-backlog.md](outbox-backlog.md) | `OutboxPendingBacklog`/`Critical` |
| 알림 이벤트 DLT 격리 (replay 자동 경로 없음) | [notification-dlt.md](notification-dlt.md) | `NotificationKafkaDltPublishedSpike` |
| Flyway 마이그레이션 실패 | [db-migration-rollback.md](db-migration-rollback.md) | 앱 기동 실패 |
| DB 손실 / K8s/리전 장애 | [disaster-recovery.md](disaster-recovery.md) | 다수 critical |

## 정기 절차 (장애 아님)

| 절차 | 런북 |
|------|------|
| 릴리스 develop → main (Sonar 기준선 점검·재고정 포함) | [release-develop-to-main.md](release-develop-to-main.md) |

## 공통 원칙

1. **금액 관련 작업은 트랜잭션 로그 남기기** — `who / when / why` 기록.
2. **DB 직접 수정 전 스냅샷** — pg_dump 또는 RDS snapshot.
3. **수정 후 대사 재실행** — `matched=true` 확인 전까지 사후 작업 미완.
4. **포스트모템 필수** — critical 알림 발동 시 `/incidents/YYYY-MM-DD-<slug>.md`.
