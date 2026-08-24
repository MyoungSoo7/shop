# Flyway Migration 컨벤션 — settlement order-service

## 📜 명명 규칙

### V1 ~ V50 (정수, *legacy*)
*이미 운영에 적용된 정수 번호 마이그레이션*. 변경하지 않는다 (운영 DB 의 `flyway_schema_history` 에 기록 보존).

> **V48 ~ V50 은 컨벤션 전환 *이후* 잘못 정수 번호로 추가된 마이그레이션** 이다 (2026-06-16 사고 원인). 운영에 적용된 상태라 강제 rename 은 *위험* 하므로 그대로 두고, *신규는 반드시 timestamp* 만 쓴다. PR 리뷰에서 정수 버전 추가 시 reject.

### V20260606003307__ 이후 (*timestamp*, 권장)
*신규 마이그레이션은 timestamp 명명* 으로 작성.

```
V{YYYYMMDDhhmmss}__{snake_case_summary}.sql

예시:
V20260606003307__add_version_to_payouts.sql
V20260607143200__add_user_status_history.sql
```

### 왜 timestamp 인가
- **병렬 PR 시 충돌 회피** — 두 개발자가 *각자 V48 으로 작성* → merge 시 *Flyway 가 거부* (같은 번호). timestamp 면 *충돌 없음* (초 단위까지 일치 어렵)
- **순서 자명** — *작성 시점* 이 곧 *적용 순서*
- **Flyway 정렬 호환** — V20260606... 은 정수 비교에서 큰 값 → V47 이후 자동 실행

### 충돌 발생 시
*과거 사례 1* (2026-06-06): PR #94 (V47__init_shedlock) 과 PR #96 (V47__add_version_to_payouts) 가 *각자 main 으로 merge* → 같은 V47 두 개 → 새 pod 시작 시 `FlywayException: Found more than one migration with version 47`.

해결:
1. *후행 PR* 의 마이그레이션을 timestamp 형식으로 rename
2. 운영 `flyway_schema_history` 에 기록된 V47 은 *그대로* (=`V47__init_shedlock.sql`)
3. rename 된 마이그레이션은 *처음 실행되는 것* 처럼 적용됨

*과거 사례 2* (2026-06-16): PR #112 가 정수 V49 / V50 추가. 그러나 *이미 V20260606003307 (타임스탬프)* 가 운영에 적용된 상태였음 → Flyway 가 V49/V50 을 *out-of-order* 로 판정해 *silent skip* → `opslab.ledger_entries` 와 `opslab.coupons.starts_at` 누락 → 새 pod CrashLoop 31시간. `application.yml` 에 `spring.flyway.out-of-order: true` 를 명시해 *과거 정수 번호도 뒤늦게 적용* 되도록 처방. 그러나 *근본 해결은 정수 번호 금지*.

### 새 마이그레이션 생성 — 빠른 명령
```bash
# 현재 시각 timestamp 생성
TS=$(date +%Y%m%d%H%M%S)
SUMMARY="add_user_status_column"
touch "order-service/src/main/resources/db/migration/V${TS}__${SUMMARY}.sql"
```

## 🔒 정책 요약
- 정수 번호 — V50 이 *마지막*, 새 PR 에선 사용 금지 (V48 ~ V50 은 컨벤션 사고)
- timestamp 명명 — *모든 신규 마이그레이션*
- 충돌 발견 시 — *후행 PR* 이 rename
- 운영 DB 의 `flyway_schema_history` *직접 수정 절대 금지* (Flyway 가 관리)
- `spring.flyway.out-of-order: true` 운영 — 정수 번호 누락 사고 안전망. 단, *재발 방지의 본질은 정수 번호 금지*.

## ⚠️ Idempotent 패턴 의무 — 2026-06-23 V50 사고

*과거 사례 3* (2026-06-23): V50 의 첫 적용 시도 가 *어떤 이유* 로 *partial 적용* 후 rollback (CHECK constraint 만 살아남음). 이후 재시도 마다 `ERROR: constraint "chk_product_variants_discount_price" for relation "product_variants" already exists` 로 무한 CrashLoop. 옛 ReplicaSet 의 pod 들 은 정상 작동 했지만 *새 deploy 가 영원히 살지 못함*.

**원인**: PostgreSQL 의 `ADD CONSTRAINT` 는 `IF NOT EXISTS` 옵션이 없음. 한 번 실패 후 *수동 정리* 안 하면 *영원히 같은 자리* 에서 멈춤.

**처방**: 새 마이그레이션 부터 *모든 `ADD CONSTRAINT` 는 *idempotent wrap* 의무*.

```sql
-- ❌ 안티 — partial 적용 후 재시도 시 fail
ALTER TABLE opslab.product_variants
    ADD CONSTRAINT chk_product_variants_discount_price
        CHECK (discount_price IS NULL OR discount_price >= 0);

-- ✅ idempotent — 이미 존재 시 silently skip
DO $$ BEGIN
    ALTER TABLE opslab.product_variants
        ADD CONSTRAINT chk_product_variants_discount_price
            CHECK (discount_price IS NULL OR discount_price >= 0);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
```

같은 패턴이 필요한 SQL:
- `ADD CONSTRAINT` (CHECK, UNIQUE, FOREIGN KEY) — `duplicate_object`
- `CREATE TRIGGER` — `duplicate_object`
- `CREATE FUNCTION` — `OR REPLACE` 사용
- `CREATE TABLE` — `IF NOT EXISTS` (이미 지원)
- `CREATE INDEX` — `IF NOT EXISTS` (이미 지원)
- `ALTER TABLE ... ADD COLUMN` — `IF NOT EXISTS` (이미 지원, V50 의 column 추가 가 이 패턴이라 정상 작동했음)

### 회복 절차 (이미 partial 상태가 된 경우)

1. *모든 변경 사항이 *DB 에 적용 됐는지* 검증 (information_schema, pg_constraint, pg_indexes 직접 확인)
2. 9/9 모두 적용 됐다면 — `flyway_schema_history` 에 *해당 version 강제 INSERT*:
   ```sql
   INSERT INTO opslab.flyway_schema_history
       (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
   VALUES (<next_rank>, '<v>', '<desc>', 'SQL', '<file>', <crc32>, '<user>', now(), 0, true);
   ```
3. checksum 은 V50 의 *Flyway CRC32* — Python `zlib.crc32(content.encode('utf-8'))`
4. 새 pod 재시작 → Flyway 가 *이미 success* 로 인식 → 다음 migration 으로 넘어감

이 절차 가 *2026-06-23 V50 사고* 의 *실제 복구 방법*. 같은 패턴 의 사고 재발 시 *동일 절차 적용 가능*.

## 📚 참고
- [Flyway naming convention](https://documentation.red-gate.com/fd/migrations-271583317.html)
- 본 프로젝트 *2026-06-06 컨벤션 전환 이전* 의 V1~V47 은 *영구 유지*.
