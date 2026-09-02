# order-service 레거시 갭 — ssgb2e / ofDentis 대조

**대상:** `MyoungSoo7/shop` `order-service` (분석 시점 main `f823c5b`, 구현 기준 main `b8da43d`)
**레거시 정본:** `MyoungSoo7/ssgb2e-front_20250721`, `MyoungSoo7/ssgb2e-quartz_20250721`, `MyoungSoo7/ofDentis_final`
**작성 기준:** `docs/plan/marketing-legacy-gap.md` — *"안 적으면 남은 것이 부채가 아니라 없던 일이 된다."*
**상태:** 2026-09-03 갱신. 아래 §5 의 다섯 항목은 `feat/batch-run-ledger` 에서 **구현됐다.**
나머지는 여전히 제안이고, 그중 넷은 *구현 전에 사람이 결정할 것*이 남아 있어 일부러 안 건드렸다.

> 사용자가 말한 `ssg_front` · `dentis` 라는 리포는 없다. 실제 이름은 위 셋이고,
> 배치(quartz)는 사용자가 언급하지 않았지만 **가장 큰 갭의 출처**라 포함했다.

---

## 0. 먼저 — 이 대조에서 레거시가 이긴 영역은 좁다

`order-service` 는 결제(분할결제·PG 라우팅·현금영수증·환불재시도), 포인트(로트·이월·정책 스코프),
반품/교환, 안심번호, 배송추적, 개인정보 동의, 판매자 등급, 통계까지 레거시보다 **넓고 깊다**.
그래서 이 문서는 기능 나열이 아니라, **증거가 있는 갭만** 남긴다.

랭킹은 크기가 아니라 *성격* 으로 한다. 최악은 **"있다고 보이는데 없는 것"** 이다 —
다음 사람이 있다고 믿고 그 위에 무언가를 얹는다.

---

## 1. 1등급 — 있다고 보이는데 없는 것

### ① `batch_run_history` — 표도 인덱스도 있고, 쓰는 코드가 0줄

`V3__add_indexes_and_constraints.sql:18` 이 만든다.

```sql
CREATE TABLE IF NOT EXISTS batch_run_history (
    id BIGSERIAL PRIMARY KEY,
    batch_name VARCHAR(100) NOT NULL,
    run_id VARCHAR(100) NOT NULL,
    target_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    processed_count INT DEFAULT 0,
    error_message TEXT
);
```

네 컬럼 전부에 인덱스가 걸려 있다. 그리고 2026-08-20 의
`V20260820110000__decommission_opslab_settlement_legacy.sql:24` 는 이렇게 못 박는다 —

> `-- [보존] outbox_events · processed_events · audit_logs · shedlock · batch_run_history 는 order 가 계속 쓰는 공유 테이블이라 대상이 아니다.`

**리포 전체에서 이 테이블을 읽거나 쓰는 Java 코드는 없다.** 한 줄도 없다.
즉 *"order 가 계속 쓴다"* 는 근거로 삭제를 면제받은 표가, 실제로는 단 한 행도 받은 적이 없다.

레거시에는 이게 살아 있다 — `dao/BatchLogDao` · `model/BatchLogVO`, 그리고 `ChangeOrderStatusJob` 은
실행마다 `batchLogDao.selectOne("batchLog.selectBatchSeq", null).getIdx()` 로 시퀀스를 받아
`totalSuccessCnt` / `totalErrorCnt` / 행별 `errorMsg` 를 적재한다.

**지금 shop 의 스케줄러 10개는 실행 결과를 어디에도 남기지 않는다.**

```
config/PartitionMaintenanceScheduler        ensureMonthly()
giftcard/.../GiftCardExpiryScheduler        expire()
order/.../GiftClaimExpiryScheduler          expire()
order/.../StockReclaimDelayScanner          scan()
payment/.../PaymentExpiryScanner            scan()
payment/.../RefundRetryScheduler            retryFailedRefunds()
point/.../PointLotExpiryScheduler           expire()
sellertier/.../SellerTierEvaluationScheduler evaluate()
shipping/.../SafetyNumberReclaimScheduler   reclaim()
shipping/.../ShippingDelayScanner           scan()
```

전부 **무인자**다. ShedLock 이 있지만 그건 *락 획득* 기록이지 *실행 결과* 가 아니다.
9/1 새벽 `PointLotExpiryScheduler` 가 절반 처리하고 던졌다면, 그 사실을 아는 곳이 없다.

### ② `target_date` 는 이미 컬럼으로 있는데, 날짜로 재실행할 방법이 없다

위 표의 `target_date` 는 우연이 아니다. 레거시 `job/reg/settlement/SettlementTargetDateResolver` 가
바로 그 축을 명시적으로 갖는다.

```java
public static final String KEY_TARGET_DATE = "targetDate";
public static final String KEY_STS_DATE = "stsDate";
// 우선순위: 1. JobDataMap "targetDate" (운영자 수동 실행)
//           2. JobDataMap "stsDate" (REST 등 외부 트리거)
//           3. 어제 일자 (일반 자정 스케줄)
```

여기에 `SettlementRerunController` 가 붙어 운영자가 특정 일자를 다시 돌린다.
shop 에는 둘 다 없다. 스케줄러가 전부 `LocalDateTime.now()` 를 읽으므로,
**놓친 날은 손으로 SQL 을 쓰는 것 말고 복구 경로가 없다.**

### ③ 그 결과가 실제로 새는 자리 — `ShippingDelayScanner`

`shipping/adapter/in/scheduler/ShippingDelayScanner.java:61`

```java
LocalDateTime now = LocalDateTime.now();
LocalDateTime crossedBefore = now.minusHours(thresholdHours);
LocalDateTime crossedAfter  = crossedBefore.minus(Duration.ofMillis(scanIntervalMs));
```

창(window)이 **시계에서만** 나온다. javadoc 이 밝힌 의도대로 "임계를 이번 창에서 막 넘어선" 건만
잡아 배송당 1회로 줄이는데, 바로 그 설계 때문에 **한 창을 건너뛰면 그 창의 배송은 영원히 신호가 안 난다.**
파드가 6시간 죽어 있었거나 `lockAtMostFor = PT30M` 를 넘겨 락이 풀렸다면 그렇게 된다.
그리고 ①이 없으므로 **건너뛰었다는 사실 자체가 기록되지 않는다.**

①②③은 한 덩어리다. 실행 원장(①)과 날짜 파라미터(②)가 생기면 ③은 재실행으로 복구된다.

### ④ `pg_reconciliation` — 마이그레이션·엔티티스캔·리포지토리스캔 3곳에 배선돼 있고 패키지가 없다

`V35__pg_reconciliation_tables.sql` 이 `opslab.pg_reconciliation_runs` 와
`opslab.pg_reconciliation_discrepancies` 를 만든다. 주석은 목적까지 적어 놨다 —
PG 일일 정산파일과 내부 원장을 비교해 차액을 4종으로 분류하고, 반올림 1원 미만은 자동 보정,
나머지는 운영자 검토 큐로.

`config/PersistenceConfig.java:36,63` 은 `github.lms.lemuel.pgreconciliation` 을
`@EntityScan` 과 `@EnableJpaRepositories` **양쪽에** 등록해 두었다.

```
$ ls order-service/src/main/java/github/lms/lemuel/pgreconciliation
ls: No such file or directory
```

패키지가 없다. 대사 코드도 없다. **금전 통제 장치가 스키마와 배선만 남고 비어 있는 상태**이고,
스캔 목록에 이름이 있어서 코드에서는 있는 것처럼 읽힌다.

> **2026-09-03 추적 — 하나가 아니라 다섯이었다.** `PersistenceConfig` 의 두 목록을 전수로 대조하니
> 실재하지 않는 패키지가 `pgreconciliation` 말고도 넷 더 있었다 — `chargeback` · `ledger` ·
> `payout` · `settlement`. 정산 기능이 settlement 서비스로 넘어가며 코드는 사라졌는데 목록만 남은
> 것이다. 다섯 개 전부 지웠다(`feat/batch-run-ledger`).
>
> 지운 것이 *기능*이 아니라 *잘못된 신호*라는 점이 중요하다. 존재하지 않는 패키지는 스캔 대상
> 0개로 조용히 넘어가므로 동작은 처음부터 지금까지 똑같다. 그런데 이 목록은 <b>열거</b>다 —
> `basePackages` 를 적는 순간 Boot 의 기본 스캔이 대체되므로, 새 도메인 패키지를 여기 추가하지
> 않으면 런타임에만 빈이 없다. 즉 이 목록은 사람이 "무엇이 있나" 를 확인하러 오는 자리인데,
> 다섯 줄이 없는 것을 있다고 말하고 있었다. `④ pg_reconciliation` 항목 자체(표·마이그레이션)는
> 그대로 남아 있고, 순위표에서 **설정 정리로 내려간다** — 구현이냐 삭제냐의 결정은 여전히 필요하다.

### ⑤ `order_status_history` — 다 적는데 아무도 못 본다

엔티티는 `order_id · previous_status · new_status · changed_by · reason · changed_at` 를 전부 남긴다.
`ChangeOrderStatusService` · `CancelOrderItemsService` 가 성실히 쓴다. 그런데 읽는 포트는 이것 하나뿐이다.

```java
// order/application/port/out/LoadOrderStatusHistoryPort.java
Optional<OrderStatus> findPreviousStatus(Long orderId, OrderStatus currentStatus);
```

리포지토리 메서드도 `findTopByOrderIdAndNewStatusOrderByIdDesc` 하나다.
**목록 조회가 없고, 이력을 보여주는 엔드포인트가 없다.** 컨트롤러 61개 중 어디에도 없다.

레거시 dentis 에는 `/mgr/market/order_state_log` 가 있다. CS 가 "이 주문 왜 이 상태냐"를 답하는 화면이다.

이건 **가장 싼 항목**이다. 데이터는 이미 다 있다. 조회 포트 1개 + 관리자 엔드포인트 1개면 끝난다.

### ⑥ `PointEarnScope.GRADE` — 자리는 잡혔고 등급이 없다

```java
// point/domain/PointEarnScope.java
// 가장 구체적인 계약이 이긴다 — CATEGORY > GRADE > GLOBAL
// Phase 1 에서 실제로 쓰는 것은 GLOBAL 뿐이다.
```

포트 시그니처까지 이미 등급을 받는다.

```java
// @param gradeKey 회원 등급 키(없으면 null) — Phase 1 에서는 항상 null
List<PointEarnPolicy> loadCandidates(LocalDate on, String gradeKey, String categoryKey);
```

호출부는 `loadCandidates(command.on(), null, null)`.
**구매회원 등급 도메인이 아예 없다** (`sellertier` 는 판매자 전용).
즉 회원등급은 새 요구사항이 아니라, 포인트 도메인이 이미 선언해 둔 계약의 없는 나머지 절반이다.

레거시 dentis 는 `MgrGradeController` 로 등급 목록·등록·**등급별 쿠폰 발급**(`/popup/before/coupon`)·
**등급 변경 이력**(`history/list`) 을 갖는다. 설계 참고 대상은 오히려 shop 자신의 `sellertier` 다 —
평가 스케줄러 + 수동 override + 정합성 점검 + outbox 이벤트 구조가 그대로 쓸 만하다.

---

## 2. 2등급 — 조용히 잃는 것

### ⑦ 만료 예고 알림이 없다

포인트·기프트카드·선물수령권은 만료 스케줄러가 돌지만 **사전 통보가 없다.**
쿠폰은 만료 스케줄러조차 없다 — `Coupon.java:103` 이 사용 시점에 `now.isAfter(expiresAt)` 로 확인하는
lazy 방식이다(이 자체는 옳다, §3 참고). 어느 쪽이든 **사용자는 예고 없이 돈을 잃는다.**

레거시에는 소멸 잡과 통보 잡이 **짝으로** 있다 —
`ExtinctionCouponJob`↔`NotiExpiredCouponJob`, `ExtinctionMileageJob`↔`NotiExpiredMileageJob`.
소멸만 옮기고 통보를 안 옮긴 셈이다.

### ⑧ 판매자에게 가는 운영 알림이 없다

`ShippingDelayScanner` 는 `OpsSignalPort` 로 `shipping.delayed` 를 Kafka 에 던진다.
받는 쪽은 **관제**다. 지연을 해소할 수 있는 사람인 **판매자에게는 아무것도 안 간다.**
게다가 `@ConditionalOnProperty(app.kafka.enabled=true)` 라 Kafka 가 꺼져 있으면 스캐너 자체가 안 뜬다.

레거시는 판매자향 통보가 4종이다 — `NotiNotyetDeliveryToSellMemberJob`(미배송),
`NotiRequestOrderToSellMemberJob`(신규주문), `NotiLockoutToSellMemberJob`(잠금예정),
`NotiSecurityCheckToSellMemberJob`(보안점검).

### ⑨ 개인정보 "보유·이용 기간" 은 고지 문자열일 뿐 강제되지 않는다

`OrderPrivacyConsent.retention` 은 `String` 이다. 동의 시점의 고지 문구를 증적으로 스냅샷하는 값이라
그 목적에는 맞지만, **파싱하거나 그 기간에 따라 파기하는 코드는 없다.**
`User.lastLoginAt` 도 필드만 있고 휴면 처리기가 없다.
레거시에는 `LockoutMngMemberJob` · `LockoutSellMemberJob` 이 있다.

법적 판단이 필요한 영역이라 여기서는 **결정 대상으로만** 올린다.

---

## 3. 일부러 안 옮길 것 (+ 이유)

| 레거시 | 안 옮기는 이유 |
|---|---|
| `RegOrderStatisticsJob` 외 정산통계 4종 (일별 사전집계) | shop 은 `SalesStatsQueryJdbcAdapter` 로 즉시 집계하고, **의미론이 더 정확하다** — `line_amount - allocated_discount` 순액, 취소 라인 제외(부분취소는 주문 상태로 안 걸러진다). 사전집계가 주는 건 *과거 수치의 불변성* 과 *규모 대응* 뿐이다. 특정 질의가 실제로 느려질 때, 잡이 아니라 **머티리얼라이즈드 뷰**로 다시 판단한다. |
| `OrderMultiController` 의 tmp주문→실주문 2단계 | shop 의 draft→validate→revalidate→confirm→discard 가 상위 호환이다. 셀 단위 오류까지 돌려준다. **단 `/gideDownload`(업로드 양식) 한 개는 없다** — 정적 파일이라 비용이 거의 없다. |
| `ExtinctionCouponJob` (쿠폰 소멸 배치) | lazy 만료가 옳다. 스위퍼를 넣으면 동기화할 상태가 하나 늘 뿐이다. 필요한 건 소멸이 아니라 **⑦ 예고 알림**과 콘솔의 "만료 예정" 조회다. |
| `GetSabangnetInvoiceDataJob`, `ChangeSamsungOrderStatusJob`, `ChangeDeliveryStatusOfOfflineOrderJob` | 특정 외부 벤더·오프라인 채널 연동. shop 에 대응 채널이 없다. **범위 밖.** |
| `TerminateCorprationCounselJob` · `TerminateSellmemberCounselJob` | shop 의 `inquiry` 와 레거시 상담(counsel)은 다른 도메인이다. inquiry 에 SLA 가 생기기 전엔 **범위 밖.** |

---

## 4. 우선순위와 현재 상태

| 순위 | 항목 | 성격 | 크기 | 상태 |
|---|---|---|---|---|
| 0 | ④ 죽은 스캔 등록 5개 정리 | 잘못된 신호 | 매우 작음 | **완료** — 동작 변화 0, 목록의 거짓말만 제거 |
| 1 | ①② 배치 실행 원장 + 날짜 재실행 | 있다고 보이는데 없음 | 중 | **완료** |
| 2 | ③ 스캐너 놓친 창 복구 | 조용히 잃음 | 작음 | **완료** — ①② 위에 얹었다 |
| 3 | ⑦ 만료 예고 알림 | 조용히 잃음 | 중 | **완료** |
| 4 | ⑤ 주문 상태 이력 조회 | 있다고 보이는데 없음 | 매우 작음 | **완료** |
| 5 | ④ pg_reconciliation 본체 | 있다고 보이는데 없음 | 큼 | **결정 대기** — 구현이냐 표 삭제냐 |
| 6 | ⑥ 회원등급 | 선언된 계약의 빈 절반 | 큼 | **결정 대기** — `sellertier` 를 형틀로 재사용 가능 |
| 7 | ⑧ 판매자향 알림 | 조용히 잃음 | 중 | **결정 대기** — ⑥ 과 같이 가면 등급별 정책까지 한 번에 |
| 8 | ⑨ 보유기간·휴면 | 결정 필요 | ? | **결정 대기** — 법적 판단 선행 |

순서가 처음 제안(⑤ → ①② → ③ → ⑦)과 달라진 이유는 **⑤가 작아서 먼저가 아니라 ①②③이
한 덩어리라서**였다. ③은 ①②가 없으면 복구할 수단 자체가 없고, ⑤는 그 셋과 독립이라
어느 순서로 넣어도 값이 같다. 작은 것부터 하는 순서가 아니라, **의존이 있는 덩어리를 먼저
닫고 독립 항목을 뒤에 붙이는** 순서가 맞았다.

아래 넷을 안 건드린 것은 크기 때문이 아니다. **코드를 쓰기 전에 사람이 정해야 할 것이 남아
있어서**다 — ④는 "이 통제를 할 것인가", ⑥은 등급 체계 자체, ⑧은 판매자에게 무엇을 언제
보낼 것인가, ⑨는 법적 보유기간 해석이다. 이런 항목을 기술적 추론만으로 밀면, 나중에 바꾸는
비용이 처음 만드는 비용보다 커진다.

### 구매확정(별도)

`order/application/service/ChangeOrderStatusService.java:331` 에 이런 주석이 있다.

> `… 별도 "구매확정" 상태가 없는 이 도메인에서는 DELIVERED 가 사실상의 확정 시점이다.`

레거시 두 곳 모두 구매확정을 별도 상태로 갖는다. 보통 이 상태가 **정산 개시 · 리뷰 작성 자격 ·
반품기한 기산점**을 한꺼번에 잠근다. 지금은 그 셋이 전부 DELIVERED 에 묶여 있다.
기능 요청이 아니라 **명시적으로 결정할 항목**으로 올린다 — 지금처럼 두더라도, 주석이 아니라 ADR 로 남겨야 한다.

---

## 5. 구현 기록 (`feat/batch-run-ledger`, 2026-09-03)

| 갭 | 무엇이 생겼나 |
|---|---|
| ④(정리) | `PersistenceConfig` 의 실재하지 않는 스캔 등록 5개 제거 + 목록이 *열거*라는 사실을 클래스 javadoc 에 명시 |
| ①② | `batch` 슬라이스 — 실행 원장 적재 · 배치별 최근 실행 조회 · `target_date` 재실행(dry-run 포함). 스케줄러 10개가 원장을 남기도록 배선 |
| ③ | 놓친 창 복구 — 스캐너의 창이 시계에서만 나오던 것을 원장 기반으로 되짚을 수 있게 |
| ⑦ | `expirynotice` 슬라이스 — D30·D7·D1 3단계, 창이 겹치지 않도록 각 단계가 배타 구간을 담당. Outbox 로 `lemuel.expirynotice.upcoming` 발행 |
| ⑤ | 상태 이력 조회 포트 + `GET /orders/admin/{orderId}/status-history` + 관리자 화면 |

### 화면을 같이 만든 것은 선택이 아니었다

`api-screen-gate` 의 `PENDING_BUDGET` 이 0 이다. 새 `@RestController` 는 ① 그것을 부르는
프론트 화면이 있거나, ② `MACHINE_ONLY` 에 사유와 함께 있거나, ③ `SCREEN_PENDING` 에 있으면서
예산을 올리거나 셋 중 하나여야 한다. ②는 사실이 아니고(둘 다 사람이 여는 운영 화면이다),
③은 부채를 지는 선택이라 ①을 골랐다 — `배치 실행 원장`·`주문 상태 이력` 2개 화면,
`App.tsx` 라우트, `menuFallback.ts` 사본, 메뉴 시드 마이그레이션까지 네 곳을 함께 고쳐야 한다.

### 토픽 하나를 등록하는 값

카탈로그(`kafka/topic-catalog.json`)는 **다섯 개 검사의 조인 키**다. `lemuel.expirynotice.upcoming`
한 줄을 넣자 순서대로 다음이 빨개졌다 —

1. `kafka-publisher-gate` — 카탈로그에 없는 토픽 발행 (등록으로 해소)
2. `contract-schema-parity-gate` — 카탈로그에만 있고 계약 스키마가 없음
3. `topic-consumer-gate` — 아무도 안 듣는데 발행 전용 선언이 없음
4. `EventContractFixtureTest` — 손으로 적은 `@ValueSource` 목록과 디스크가 불일치
5. `EventContractMoneyTypeTest` — 스키마 본문에 `"JSON number"` 라는 문자열이 있음

5번은 부분문자열 검사라 *"수치로 실으면 정밀도를 잃는다"* 는 **부정문까지** 잡는다.
게이트가 무딘 것은 맞지만 의도는 옳아서, 게이트를 고치는 대신 문장을 바꿨다.

**즉 토픽 등록은 3자 약속이다** — 카탈로그 항목 + 계약 스키마(+정본 샘플) + 소비자(또는 명시적
발행 전용 선언). 이 저장소는 그 셋 중 하나라도 빠지면 CI 가 선다.

### 발행 전용에는 두 종류가 있다

기존 항목들은 소비자가 **경계 밖**(정산·GL)이라 안 붙는 것이고, `expirynotice` 는 **붙을 자리가
이 저장소 안인데 아직 없는** 것이다. SPEC.md §5 는 2026-08-28 에 이 둘을 같은 문장으로 덮어쓴
탓에 여섯 토픽이 "소비자가 생길 리 없다"고 잘못 주장하고 있었고, partner-service 가 생기자
여섯이 한꺼번에 빠졌다. 같은 실수를 반복하지 않으려고 이번 항목은 사유를 다르게 적었다.
