# Runbook — Flyway 마이그레이션 실패

## 증상

애플리케이션 기동 실패, 로그에 `FlywayException: Migration ... failed` 또는 `Validate failed`.

## 원칙

1. **`flyway_schema_history` 를 직접 수정하지 말 것.** 수정하면 향후 마이그레이션 자동 적용이 깨진다.
2. **전진 롤백(forward fix)** 을 기본으로 한다. V{N} 이 실패하면 V{N+1} 에 보정 스크립트를 작성해 이어서 적용.
3. 예외: 운영 중단 수준의 치명적 상황에서만 스냅샷 복구 고려.

## 1. 원인 분류

```sql
-- 실패한 마이그레이션 이력
SELECT version, description, success, installed_on, execution_time
FROM opslab.flyway_schema_history
ORDER BY installed_rank DESC LIMIT 10;
```

- `success=false` 행이 있으면 실패 지점 확인.
- 체크섬 불일치(validate failure) 면 V{N} SQL 파일을 변경한 적이 있는 것.

## 2. 실패 케이스별 대응

### (A) SQL 오류로 V{N} 실패 (success=false)

1. DB 상태 수동 확인 — 부분 적용됐는지.
2. 부분 적용 → 수동으로 롤백 쿼리 실행 (DROP 된 테이블 복원 등).
3. `flyway_schema_history` 에서 실패 행 삭제:
   ```sql
   DELETE FROM opslab.flyway_schema_history WHERE version = ?;
   ```
   (**실패 행만 — 성공한 이전 행은 절대 건드리지 말 것**)
4. V{N} SQL 을 수정하고 재실행.

### (B) 체크섬 불일치 (validate failure)

- 이미 적용된 V{N} 의 SQL 파일이 변경됨.
- 복구 1: `flyway.validate-on-migrate=false` 로 임시 기동 후 V{N+1} 에 수정사항 담기.
- 복구 2: `./gradlew flywayRepair` 로 체크섬 재계산 (운영에서는 신중).

### (C) 컬럼 누락 / 스키마 드리프트 (Hibernate validate 실패)

- 엔티티가 요구하는 컬럼이 DB 에 없음.
- V{N+1} 에 `ALTER TABLE ... ADD COLUMN` 추가.
- `SchemaIntegrationTest` 로 재발 방지.

## 3. 운영 적용 절차

- 배포 전 staging 에서 `./gradlew flywayMigrate` 로 선 검증.
- 운영은 앱 기동 시 자동 적용 (Spring Boot + `spring-boot-flyway` 모듈이 있어야 함 — ADR 0009 참조).
- pg_dump 백업을 배포 직전 확보.

## 4. 롤백 (최후 수단)

- Flyway 는 기본적으로 undo 마이그레이션을 실행하지 않음 (Community edition).
- 롤백이 필요하면 **pg_restore** 로 스냅샷 복원 + 이후 버전 재적용.
- RPO 는 PITR 설정에 따름 (보통 5분 간격 WAL 아카이빙).

## 5. 방지

- V{N} 작성 시 NULL 허용·기본값 명시 → backward-compatible 변경 우선.
- 신규 NOT NULL 컬럼은 2단계: (V{N}) NULL 추가 → (V{N+1}) 값 채우기 → (V{N+2}) NOT NULL 제약.
- 트리거·함수는 `CREATE OR REPLACE` 로 idempotent 하게.
