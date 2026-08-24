# Runbook — Disaster Recovery

## 범위

- PostgreSQL 원장 손실 (하드웨어 장애, 실수 DROP, 보안 사고).
- K8s 클러스터 전체 장애.
- 클라우드 리전 장애.

## RPO / RTO (목표)

| 지표 | 목표 |
|------|------|
| RPO (Recovery Point Objective) | ≤ 5분 (WAL 기반 PITR) |
| RTO (Recovery Time Objective) | ≤ 2시간 (복구 + 검증 포함) |

## DB 복구 절차

### 1. 최신 스냅샷 + WAL 확인

```bash
# WAL 아카이브 위치
aws s3 ls s3://lemuel-wal-archive/ --recursive | tail -20

# 최근 스냅샷
aws rds describe-db-snapshots --db-instance-identifier lemuel-prod
```

### 2. PITR 복구

```bash
aws rds restore-db-instance-to-point-in-time \
    --source-db-instance-identifier lemuel-prod \
    --target-db-instance-identifier lemuel-prod-recovery \
    --restore-time "2026-04-23T12:00:00Z" \
    --db-instance-class db.m6i.xlarge
```

### 3. 애플리케이션 연결 전환

- Route53 weighted record 전환 또는 `SPRING_DATASOURCE_URL` 시크릿 갱신 후 rollout.
- Flyway 는 `flyway_schema_history` 가 복구된 DB 에 있으므로 자동으로 통과.

### 4. 정합성 검증

- `SchemaIntegrationTest` 로 스키마-엔티티 검증.
- `/api/reports/cashflow` 로 대사 3 불변식 확인 (특히 inv 3 — 이벤트 vs 정산).
- 불일치 발견 시 `cashflow-reconciliation.md` 런북 진입.

## K8s 클러스터 장애

- `kubectl apply -f k8s/` 를 복구 클러스터에 재적용.
- Secrets/ConfigMap 은 별도 백업 store 에서 복원.
- Ingress DNS 전환.

## 리전 장애

- DR 리전에 warm standby (PostgreSQL read replica + K8s 기본 manifests).
- 수동 promote: read replica → primary, DR 리전 K8s 스케일업.

## DR 훈련

- **분기 1회** 복구 훈련 (스테이징에서).
- 훈련 시 측정: RTO 실측값, 누락된 수동 단계.
- 결과를 본 문서에 업데이트.

## 사전 준비 체크리스트

- [ ] pg_dump 일 1회 + WAL 연속 아카이브 작동 확인.
- [ ] Secrets 백업 store 분리 (AWS Secrets Manager cross-region replication).
- [ ] K8s manifests git 저장소 미러링 (GitHub + 내부 Gitea).
- [ ] 팀 연락망 + on-call 스케줄 최신화.
