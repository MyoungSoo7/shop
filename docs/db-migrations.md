# DB 마이그레이션 규약 (Flyway)

마이그레이션을 가진 서비스는 **4개**다. 각자 자기 스키마를 가지며, 서로의 테이블을 건드리지 않는다.

| 서비스 | 스키마 | 마이그레이션 |
| --- | --- | --- |
| `order-service` | `opslab` | 182 |
| `operation-service` | `opslab` | 24 |
| `marketing-service` | `marketing` | 5 |
| `partner-service` | `partner` | 2 |

*(2026-08-31 실측 — `git ls-files '*/src/main/resources/db/migration/*.sql' | wc -l`)*

---

## 📜 명명 규칙 — 신규는 예외 없이 타임스탬프

```
V{YYYYMMDDhhmmss}__{snake_case_summary}.sql

TS=$(date +%Y%m%d%H%M%S)
touch "marketing-service/src/main/resources/db/migration/V${TS}__add_campaign_note.sql"
```

**정수 번호(`V51`, `V6` …)는 새로 만들지 않는다.** 권장이 아니라 기계 강제다 —
`scripts/harness/test/migration-version-gate.test.mjs` 가 서비스별 기준선을 넘는 정수 버전을 FAIL 시킨다.

### 기준선 — 정수 블록이 닫힌 지점

| 서비스 | 정수 블록 | 타임스탬프 전환 |
| --- | --- | --- |
| `order-service` | `V1` ~ **`V50`** (V21 결번) | `V20260606120000` 부터 |
| `operation-service` | `V1` ~ **`V4`** | `V20260715130000` 부터 |
| `marketing-service` | `V1` ~ **`V5`** | 아직 없음 — 다음 것부터 타임스탬프 |
| `partner-service` | `V1` ~ **`V2`** | 아직 없음 — 다음 것부터 타임스탬프 |

이 숫자는 **올라가지 않는다.** 올리고 싶다는 것은 정수 버전을 새로 만들었다는 뜻이고, 그게 정확히
막으려는 것이다. 이미 배포된 DB 의 `flyway_schema_history` 에 기록이 남아 있어 rename 도 못 한다.

---

## 왜 정수 번호가 위험한가

Flyway 의 버전 비교는 **숫자**다. `V51` 은 51, `V20260606120000` 은 20260606120000 이다.
그래서 타임스탬프 버전이 이미 적용된 DB 에 정수 `V51` 을 올리면 그 파일은 "최신"이 아니라
**이미 적용된 것들보다 아래로 끼어든다**(out-of-order).

여기가 이 결함의 성질이다:

- **CI 는 못 잡는다.** Testcontainers 가 매번 빈 DB 를 만든다. 빈 DB 에는 out-of-order 라는 개념이
  없다 — 전부 처음 적용되므로 정렬만 맞으면 초록이다.
- **배포된 DB 에만 이력이 있다.** 그래서 이 결함은 CI 를 통과한 뒤 **기동 시점에만** 드러난다.

### 실제 사고 3건

> 이 3건은 **이 저장소보다 앞선다.** order-service 의 정수 블록은 커머스 축을 떼어 오기 전
> 원본 저장소(settlement)에서 만들어졌고, 첫 커밋 `a1a5a1d` 시점에 이미 지금 모양이었다.
> 그래서 여기 git 이력으로는 재현되지 않는다 — 남은 것은 파일 이름과 이 문서뿐이다.

**① 2026-06-06 — 같은 번호 충돌.** 두 PR 이 각자 `V47` 을 잡은 채 각자 머지되어 같은 번호가 둘.
새 파드 기동 시 `FlywayException: Found more than one migration with version 47`.
→ 당시 처방은 **후행 PR 을 정수로 재번호**(`V48__init_shedlock`)였고, 그 선택이 정수 블록을
V50 까지 밀어 올려 사고 ②의 밑밥이 됐다. 지금 규약에서는 후행 PR 을 **타임스탬프로 rename** 한다.

**② 2026-06-16 — out-of-order 조용한 스킵 (31시간 CrashLoop).** PR #112 가 정수 `V49`/`V50` 을 추가했는데
그때 이미 타임스탬프 버전이 운영에 적용돼 있었다. order-service 는 `validate-on-migrate: false` 라서
Flyway 가 실패하지 않고 **조용히 건너뛰었고**, `opslab.ledger_entries` 와 `opslab.coupons.starts_at` 이
없는 채로 새 파드가 31시간 CrashLoop 했다.
→ 사후 처방으로 order-service 에 `out-of-order: true`. **다만 그건 안전망이지 예방이 아니다** —
예방은 "정수 버전을 더 안 만드는 것" 하나뿐이고, 그건 위 게이트가 한다.

**③ 2026-06-23 — 부분 적용 후 무한 재시도.** `V50` 이 partial 적용 후 롤백되며 CHECK 제약만 살아남아,
이후 재시도마다 `constraint "chk_product_variants_discount_price" ... already exists` 로 CrashLoop.
→ 아래 idempotent 패턴 의무화.

---

## 서비스별 Flyway 설정이 다르다 — 일부러 다르다

| | `order-service` | 나머지 3개 |
| --- | --- | --- |
| `out-of-order` | `true` | 미설정(기본 `false`) |
| `validate-on-migrate` | `false` | 미설정(기본 `true`) |
| `ignore-migration-patterns` | `"*:missing"` | 미설정 |

order-service 의 두 값은 **사고 ②의 사후 처방과 데모 시드 정리의 잔재**다. 그 조합이 곧 "조용히
스킵된" 이유이기도 하다 — 검증을 끄면 Flyway 는 어긋난 이력을 예외 대신 침묵으로 처리한다.

나머지 3개는 기본값이다. 그래서 만약 정수 버전이 새로 끼어들면 **기동이 시끄럽게 실패한다.**
조용한 스킵보다 낫기 때문에 order-service 설정을 복사하지 않았다. order-service 쪽을 되돌리는 것은
운영 이력이 걸린 별개 작업이라 여기서 다루지 않는다.

---

## ⚠️ Idempotent 패턴 의무 (사고 ③)

PostgreSQL 의 `ADD CONSTRAINT` 에는 `IF NOT EXISTS` 가 없다. 한 번 부분 적용되면 수동 정리 전까지
영원히 같은 자리에서 멈춘다.

```sql
-- ❌ 안티 — partial 적용 후 재시도 시 fail
ALTER TABLE opslab.product_variants
    ADD CONSTRAINT chk_product_variants_discount_price
        CHECK (discount_price IS NULL OR discount_price >= 0);

-- ✅ idempotent — 이미 존재하면 조용히 통과
DO $$ BEGIN
    ALTER TABLE opslab.product_variants
        ADD CONSTRAINT chk_product_variants_discount_price
            CHECK (discount_price IS NULL OR discount_price >= 0);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
```

| SQL | 처방 |
| --- | --- |
| `ADD CONSTRAINT` (CHECK·UNIQUE·FK) · `CREATE TRIGGER` | `DO $$ … EXCEPTION WHEN duplicate_object THEN NULL; END $$` |
| `CREATE FUNCTION` | `OR REPLACE` |
| `CREATE TABLE` · `CREATE INDEX` · `ALTER TABLE ADD COLUMN` | `IF NOT EXISTS` (문법이 지원한다) |

`CREATE EXTENSION` 은 운영 DB 에서 권한 거부로 죽을 수 있다 — 필요하면 `DO` 블록으로 감싸
없을 때만 시도하게 한다.

### 회복 절차 (이미 partial 상태인 경우)

1. 해당 마이그레이션의 **모든** 변경이 DB 에 반영됐는지 직접 확인
   (`information_schema` · `pg_constraint` · `pg_indexes`).
2. 전부 반영됐다면 `flyway_schema_history` 에 그 버전을 성공으로 기록한다.

   ```sql
   INSERT INTO opslab.flyway_schema_history
       (installed_rank, version, description, type, script, checksum,
        installed_by, installed_on, execution_time, success)
   VALUES (<next_rank>, '<v>', '<desc>', 'SQL', '<file>', <crc32>, '<user>', now(), 0, true);
   ```

3. `checksum` 은 Flyway 의 CRC32 — Python `zlib.crc32(content.encode('utf-8'))`.
4. 파드 재시작 → Flyway 가 이미 성공으로 보고 다음 마이그레이션으로 넘어간다.

> `flyway_schema_history` 직접 수정은 **이 회복 절차 외에는 금지**다. 평상시 정본은 Flyway 다.

---

## 참고

- [Flyway naming convention](https://documentation.red-gate.com/fd/migrations-271583317.html)
- 게이트: `scripts/harness/test/migration-version-gate.test.mjs`
- 메뉴 시드 마이그레이션은 화면 추가 절차의 일부다 → [README 화면 추가](../README.md#화면을-하나-더-붙일-때-2스텝--1가드)
