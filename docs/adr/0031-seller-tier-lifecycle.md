# ADR 0031 — 셀러 등급 라이프사이클 (자동 산정 + 변경 이력 + 강등 유예)

- 상태: **Accepted (착지 완료)** — 2026-08-08 구조 구현, 2026-08-09 임계·유예 수치 승인
  - **승인된 값**: VIP 5억 / STRATEGIC 30억(12개월 결제 순액), 강등 유예 3개월 + 연속 미달 2회.
    `../../order-service/src/main/resources/application.yml` 의 `app.seller-tier.*` 에 명시했고, 환경변수
    (`APP_SELLER_TIER_*`)로 배포별 재정의가 가능하다 — 재승인 시 코드 변경·배포 불필요.
  - **자동 재산정 스케줄러는 계속 비활성**(`app.seller-tier.auto-evaluate.enabled=false`)이다.
    수치가 정해졌다고 사람 확인 없이 수수료·정산주기·홀드백이 바뀌게 두지 않는다 — 운영 절차는
    `POST /admin/seller-tiers/evaluate`(미리보기) → 결과 확인 → `?dryRun=false`(반영).
  - 임계 분포는 데이터로 유도하지 못했다: 개발 DB 에 셀러가 1명뿐(12개월 순액 12.2억)이라
    `../../scripts/sim/tier_threshold_simulation.sql` 이 분포를 낼 수 없었다. 운영 데이터가 쌓이면
    같은 스크립트로 재검토할 것.
- 일자: 2026-08-06
- 관련: ADR 0014(등급별 T+N 정산 주기 — 등급을 **소비**하는 쪽), ADR 0015(등급별 홀드백),
  ADR 0020(order↔settlement DB 분리), ADR 0024(이벤트 계약-as-code), ADR 0032(수수료율 유효기간 정책),
  `settlement-domain-rules`·`order-commerce-rules` 스킬
- 배경: ADR 0014 는 등급을 **읽어서** 요율·주기·홀드백을 결정하는 구조를 세웠지만, 그 등급 값이
  **어떻게 정해지고 바뀌는지는 정의하지 않았다**. 본 ADR 이 그 공백을 메운다.

## 컨텍스트

`SellerTier` 하나가 셀러 경제조건 **3축을 동시에** 결정한다 — 수수료율·정산주기·홀드백률
(`SellerTier.java:11-15`, ADR 0014 §1, ADR 0015). 그만큼 값이 무겁다. 그런데 값의 라이프사이클은 비어 있다.

### 현행 (2026-08-06 확인)

| 항목 | 상태 |
|---|---|
| 저장 | `users.seller_tier VARCHAR(20) NOT NULL DEFAULT 'NORMAL'` + CHECK 제약 (`V32__seller_tier_and_commission_rate.sql:10-14`) |
| 변경 유스케이스 | **없음** — order-service `user` 패키지에 Tier 를 다루는 클래스 0건 |
| 변경 이력 | **없음** — 이력 테이블·감사 훅 없음 |
| 산정 기준 | **없음** — 문서에도 코드에도 승급/강등 조건이 정의된 적 없음 |
| 소비 경로 | order: `SellerSettlementMetaJdbcAdapter`(payments→orders→products→users 조인, 이벤트 payload 에 동봉) → settlement: `SettlementPaymentViewJpaEntity.sellerTier` 프로젝션 (`SellerMetaProjectionAdapter`) |

### 결함 1 — 돈에 직결되는 값이 흔적 없이 바뀐다

셀러를 NORMAL→VIP 로 올리면 수수료가 3.5%→2.5%, 정산이 T+7→T+3, 홀드백이 30%→10% 로 **동시에** 움직인다.
이 변경의 유일한 경로가 DB 수기 UPDATE 이고, **누가·언제·왜 바꿨는지 남지 않는다**. 금융 도메인에서
가격 조건 변경이 감사되지 않는 것은 그 자체로 결함이다(`compliance-review` 기준: 이력 보존·권한).

### 결함 2 — 승급/강등 기준이 없어 영업 재량에 열려 있다

"거래액 얼마부터 VIP" 라는 기준이 코드에도 문서에도 없다. 셀러 입장에서는 등급 상승 경로가 불투명하고,
운영 입장에서는 요청이 올 때마다 개별 판단이 필요하다.

### 결함 3 — 그냥 자동화하면 **등급 진동**이 생긴다

기준을 정해 자동 산정만 붙이면, 임계 근처 셀러가 월별 매출 변동으로 VIP↔NORMAL 을 왕복한다. 그때마다
수수료·정산주기·홀드백이 함께 출렁이고, 셀러는 매달 다른 지급일과 다른 실수령액을 받는다. **자동 산정은
강등 억제 장치와 한 묶음이어야 한다** — 이것이 본 ADR 이 산정·이력·유예를 하나로 다루는 이유다.

> 사례 조사: 동종 커머스 코드베이스(`ofDentis`, 회원 등급 도메인)는 `tb_grade_master.maintain_monthly`
> (등급 유지기간) + `tb_grade_member_history.maintain_date` 로 정확히 이 문제를 다루고 있었다. 다만
> 그 구현에서 **강등 분기는 빈 `if` 블록으로 미구현**이었다 — 유예 개념은 맞게 잡고 실행을 못 끝낸 사례로,
> 우리는 이 지점을 처음부터 테스트로 못박는다.

## 결정 포인트 (오너 확정 필요)

### 1. 산정 근거를 어디서 집계하는가?

| 옵션 | 집계원 | 평가 |
|---|---|---|
| **(a) order-service 자기 DB** | `payments` CAPTURED 금액 − 환불액, 셀러별 12개월 | **권장.** MSA 경계 무손상, 왕복 0. settlement 확정 순액과 미세 차이가 날 수 있으나 등급은 **구간 판정**이라 근사로 충분하다(대사 대상 아님) |
| (b) settlement 확정 정산액 | `settlements.net_amount` 합 | 회계적으로 더 정확하지만, 등급 컬럼 소유자는 order(`users`)라 **역방향 이벤트 왕복**이 필요해진다 |

→ **(a) 권장.** 단, "등급 산정 기준액은 회계 확정액이 아니라 결제 기준 순액"임을 셀러 고지 문구에 명시해야 한다.

### 2. 강등 정책은?

| 옵션 | 평가 |
|---|---|
| 즉시 강등 | 결함 3(진동) 직격. ✗ |
| **유예 강등** | 승급은 즉시, 강등은 `demotion_guard_until` 경과 **그리고** 연속 K회 미달일 때만. **권장** |
| 강등 없음(관리자만) | 안전하지만 등급 인플레이션 — 전원이 최고 등급으로 수렴 |

→ **유예 강등 권장.** 기본값 제안: 유예 3개월 · 연속 2회(주간 평가 기준이 아니라 **월 평가 2회**) 미달.
  구체 수치는 오너 확정 사항.

### 3. 등급 변경은 소급되는가?

→ **비소급.** 이미 ADR 0014 §4 가 "정산 시점 요율 스냅샷 · 과거 정산 재계산 금지"를 못박았고
(`Settlement.commissionRate`, V32), 등급 변경은 **미래 정산에만** 반영된다. 이는 새 결정이 아니라
기존 원칙의 재확인이며, 본 ADR 은 이 원칙을 깨지 않는다.

## 제안 (세 옵션)

### 옵션 A — 이력만 추가 (자동 산정 없음)
관리자 API + `seller_tier_history` 만 만든다.
- 장점: 최소 변경, 결함 1 해소.
- 단점: 결함 2·3 미해소. 기준 없는 재량 변경이 그대로 남는다.

### 옵션 B — 자동 산정 + 이력 + 강등 유예 (**권장**)
아래 설계 참조.
- 장점: 결함 1·2·3 동시 해소. 셀러에게 제시 가능한 등급 정책 확보.
- 단점: 신규 테이블 2 · 스케줄러 1 · 이벤트 토픽 1 추가. 집계 쿼리 비용.

### 옵션 C — settlement 이 산정하고 order 에 통보
- 단점: 등급 컬럼 소유(order)와 산정 주체(settlement)가 갈려 **양방향 이벤트**가 생긴다. ADR 0020 이
  세운 단방향 프로젝션 구도를 흐린다. ✗

## 설계 (옵션 B)

### 1. 소유·배치

order-service 에 `sellertier` 도메인 신설 (`msa-service-wiring` 스킬 5곳 배선 절차 준수).

```
order-service/src/main/java/github/lms/lemuel/sellertier/
├── domain/
│   ├── SellerTierPolicy.java      # 구간표 — 임계 거래액 → 목표 등급
│   ├── TierAssignment.java        # 애그리거트: current, effectiveFrom, demotionGuardUntil, consecutiveMissCount
│   ├── TierDecision.java          # PROMOTE | DEMOTE | HOLD | GUARDED(강등 유예로 보류)
│   └── TierChangeReason.java      # AUTO_PROMOTION | AUTO_DEMOTION | ADMIN_OVERRIDE
├── application/port/in/           # EvaluateSellerTiersUseCase(dryRun) · OverrideSellerTierUseCase
│   ├── port/out/                  # LoadSellerSalesAggregatePort · SaveTierAssignmentPort
│   │                              # AppendTierHistoryPort · PublishTierChangedPort
│   └── service/
└── adapter/
    ├── in/batch/SellerTierEvaluationScheduler
    ├── in/web/AdminSellerTierController        # 조회 · dryRun · 관리자 override
    └── out/{persistence,event}/
```

### 2. 스키마

```sql
-- 현재 등급 (정본)
CREATE TABLE opslab.seller_tier_assignment (
    seller_id              BIGINT      PRIMARY KEY,
    tier                   VARCHAR(20) NOT NULL,
    effective_from         DATE        NOT NULL,
    demotion_guard_until   DATE,                    -- NULL = 유예 없음
    consecutive_miss_count SMALLINT    NOT NULL DEFAULT 0,
    last_evaluated_at      TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_sta_tier CHECK (tier IN ('NORMAL','VIP','STRATEGIC'))
);

-- 변경 이력 (append-only, UPDATE/DELETE 금지)
CREATE TABLE opslab.seller_tier_history (
    id                  BIGSERIAL   PRIMARY KEY,
    seller_id           BIGINT      NOT NULL,
    prev_tier           VARCHAR(20),               -- 최초 부여 시 NULL
    new_tier            VARCHAR(20) NOT NULL,
    reason              VARCHAR(32) NOT NULL,      -- TierChangeReason
    basis_amount        NUMERIC(18,2),             -- 판정 근거 거래액 (BigDecimal)
    basis_period_start  DATE,
    basis_period_end    DATE,
    changed_by          VARCHAR(64) NOT NULL,      -- 'SYSTEM' 또는 관리자 식별자
    memo                VARCHAR(255),              -- ADMIN_OVERRIDE 시 필수
    changed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sth_seller ON opslab.seller_tier_history (seller_id, changed_at DESC);
```

**`users.seller_tier` 는 읽기 캐시로 존치한다.** assignment 가 정본이고, 같은 트랜잭션에서 컬럼을 동기화한다.
이유: 현행 소비 경로(`SellerSettlementMetaJdbcAdapter` 의 GROUP BY 조인, `PaymentCaptured` payload 동봉)를
**한 줄도 건드리지 않고** 착지시키기 위함. 캐시 드리프트는 정합성 스위트에 검사 1건을 추가해 감시한다.

### 3. 도메인 규칙 (테스트로 못박을 불변식)

1. **승급은 유예를 무시한다** — `demotionGuardUntil` 이 미래여도 상위 등급 조건을 만족하면 즉시 승급.
2. **강등은 두 조건을 모두 만족해야 한다** — `today > demotionGuardUntil` **AND** `consecutiveMissCount >= K`.
   하나라도 불만족이면 `GUARDED` 로 기록만 남기고 등급은 유지한다(이력에 남겨 "왜 안 내려갔는지" 설명 가능).
3. **관리자 override 는 유예를 재설정한다** — 수동 승급 후 다음 평가에서 곧바로 강등되는 사고 방지.
4. **판정은 순수 함수다** — `SellerTierPolicy.tierFor(net12m)` + `TierAssignment.decide(target, today,
   missThreshold)` 는 DB·시계에 접근하지 않고 상태도 바꾸지 않는다(미리보기와 실행이 같은 판정을 본다).
   (사례 조사 대상 코드베이스는 이 판정을 80줄 UNION 중첩 SQL 에 넣어 테스트가 불가능했다 — 반면교사.)
5. 금액은 전부 `BigDecimal`. 등급 임계·거래액에 `double`/`float` 금지.
6. `TierAssignment` 는 public setter 금지 — 전이 메서드(`apply`/`overrideTo`)만 노출 (OO 게이트).

### 4. 이벤트

신규 토픽 `lemuel.seller.tier_changed` — Outbox 발행(직접 publish 금지).

```
{ eventId, sellerId, prevTier, newTier, reason, effectiveFrom, basisAmount, occurredAt }
```

- 컨슈머: settlement — `settlement_user_view` 의 등급 갱신(운영 조회·리포트용).
- **정산 계산에는 쓰지 않는다.** 정산은 지금처럼 `PaymentCaptured` 에 동봉된 결제 시점 등급을 쓴다
  (비소급 원칙, 결정 포인트 3).
- 스키마·정본 샘플·양방향 계약 테스트는 ADR 0024 절차(`event-contract-change` 스킬)로 배선.

### 5. 스케줄러

```java
@Scheduled(cron = "${app.seller-tier.evaluate-cron:0 0 3 1 * *}", zone = "Asia/Seoul")  // 매월 1일 03:00
@SchedulerLock(name = "order-seller-tier-evaluate", lockAtMostFor = "PT30M")
```

- **락 이름은 전역 유일해야 한다.** 사례 조사에서 한 스케줄러 클래스가 `name="cancelMarketDeposit"` 을
  3개 메서드에, `name="cancelEduDeposit"` 을 2개 메서드에 중복 지정하고 `lockAtLeastFor` 를 23~24시간으로
  둬서, **하루에 그중 하나만 실행되고 나머지는 조용히 스킵되는** 실장애 패턴을 확인했다. 컴파일도 CI 도 못 잡는다.
  → 본 ADR 과 함께 **락 이름 유일성 게이트 테스트**를 추가한다(현재 우리 9개 이름은 전부 유일함을 확인).
- 실패를 삼키지 않는다 — 예외는 로깅 후 재던지고, 평가 실패 건수를 ops 신호로 발행한다.

### 6. dryRun

`GET /admin/seller-tiers/evaluate?dryRun=true` 가 확정 없이 "승급 12 · 강등 3 · 유예보류 5" 와 셀러별 근거를
반환한다. 운영자가 확인 후 확정 실행. (같은 dryRun 프로토콜을 payout 배치에도 적용할 예정 — 별도 작업.)

## 결과

### 좋아지는 점
- 등급 변경이 **기준·근거·주체와 함께** 이력으로 남는다(감사 가능).
- 셀러에게 제시 가능한 등급 정책이 생긴다(승급 경로 투명).
- 강등 유예로 경제조건 진동이 억제된다.
- 관리자 override 가 정식 경로가 되어 DB 수기 UPDATE 가 사라진다.

### 트레이드오프 / 리스크
- 테이블 2 · 스케줄러 1 · 토픽 1 증가. 계약 테스트 배선 비용.
- `users.seller_tier` 캐시 드리프트 가능성 → 정합성 검사 1건으로 상쇄.
- 12개월 거래액 집계 비용 — 셀러 수 × 기간. 월 1회 배치이므로 감내 가능하나 인덱스 확인 필요.
- 등급 상승이 곧 **수수료 수입 감소**다. 임계값은 재무 오너 승인 사항.

### 후속
- CATEGORY 별 요율 예외는 ADR 0032 에서 다룬다(본 ADR 범위 밖).
- 등급별 자동 쿠폰/혜택 지급은 범위 밖 — 다만 "(대상, 주기버킷)" 멱등키 패턴을 그때 재사용한다.

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 현행 유지(수기 UPDATE) | ✗ | 감사 불가 · 기준 부재 |
| 이력만 추가(옵션 A) | ✗ | 결함 2·3 미해소 |
| **자동 산정 + 이력 + 유예(옵션 B)** | ✓ | 세 결함 동시 해소, 기존 스냅샷 원칙과 충돌 없음 |
| settlement 이 산정(옵션 C) | ✗ | 양방향 이벤트 — ADR 0020 단방향 구도 훼손 |
| 즉시 강등 | ✗ | 경제조건 진동 |

## 구현 체크리스트 (완료 판정은 게이트가 정답)

- [x] `SellerTierPolicy` 순수 함수 단위 테스트 — 승급/강등/유예/경계값 (`SellerTierPolicyTest`)
- [x] `TierAssignment` 불변식 테스트 — 유예 중 강등 차단, override 시 유예 재설정 (`TierAssignmentTest`)
- [x] Flyway `V20260808100000__seller_tier_lifecycle.sql`
- [x] `lemuel.seller.tier_changed` 스키마 + 정본 샘플 + 양방향 계약 테스트 (ADR 0024) —
      프로듀서 `SellerTierEventContractTest`, 컨슈머 `EventContractConsumerTest`(settlement),
      소비측 컬럼 `V20260808110000__settlement_user_view_seller_tier.sql`
- [x] 관리자 지정 API — `POST /admin/seller-tiers/{sellerId}/override`(사유 필수, 유예 재설정)
- [x] ShedLock 이름 유일성 게이트 테스트 (`../../scripts/harness/test/scheduler-lock-gate.test.mjs`)
- [x] dryRun 응답 계약 테스트 (`AdminSellerTierControllerTest`)
- [x] `users.seller_tier` 캐시 정합 검사 — `GET /admin/seller-tiers/integrity`(읽기 전용).
      **settlement 의 `/admin/integrity` 가 아니라 order 에 두었다**: 정본·캐시가 둘 다 order DB(opslab)에
      있어 settlement 가 조회하면 cross-DB 가 되고 ADR 0020 경계를 깬다.
      드리프트를 종류로 분류한다(`CACHE_STALE`/`CACHE_MISSING`/`AUTHORITY_MISSING`) — 복구 절차가
      다르기 때문. 고치지는 않는다: `AUTHORITY_MISSING` 은 "무엇이 옳은 등급인가"를 사람이 정해야 하고,
      일괄 덮어쓰기를 넣으면 그 판단 없이 돈이 움직인다.
- [x] settlement 측 등급 컬럼 초기 백필 — `POST /admin/settlement-projection/backfill` 이 등급 정본을
      `BACKFILL` 사유로 재발행한다. 발효일은 정본 값을 그대로 싣는다(실행일로 덮으면 "언제부터 그
      등급이었나"가 사라진다). 컨슈머 upsert + `processed_events` 로 여러 번 실행해도 안전.
- [x] `./gradlew :order-service:test :order-service:jacocoTestCoverageVerification` (LINE 90%) 통과

- [x] 임계·유예 수치 승인 및 설정 반영 (2026-08-09) — 아래 표

| 설정 키                                | 값         | 의미                                          |
| -------------------------------------- | ---------- | --------------------------------------------- |
| `app.seller-tier.vip-threshold`         | 500000000  | 12개월 결제 순액 5억 이상 → VIP               |
| `app.seller-tier.strategic-threshold`   | 3000000000 | 30억 이상 → STRATEGIC                         |
| `app.seller-tier.guard-months`          | 3          | 승급 후 3개월간 강등 없음(관리자 지정도 동일) |
| `app.seller-tier.miss-threshold`        | 2          | 유예 후에도 연속 2회 미달해야 강등            |
| `app.seller-tier.auto-evaluate.enabled` | false      | 자동 실행 안 함 — 미리보기 후 수동 반영       |

> **여전히 남는 안전장치**: 스케줄러가 꺼져 있으므로 이 수치를 넣은 것만으로는 어떤 셀러의 등급도
> 바뀌지 않는다. 첫 반영은 운영자가 미리보기(`dryRun=true`)로 "누가 어떻게 바뀌는지"를 확인한 뒤
> 명시적으로 `dryRun=false` 를 호출해야 일어난다.
