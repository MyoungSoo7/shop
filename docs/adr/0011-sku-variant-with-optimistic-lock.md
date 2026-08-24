# ADR 0011 — SKU 변형(Variant) + 원자적 조건부 UPDATE 재고 차감

- 상태: Accepted
- 일자: 2026-03-09

> 파일명의 `optimistic-lock` 은 최초 설계(낙관적 락 재시도)의 흔적이다. 핫 차감 경로의 결정은
> 아래처럼 **단일 원자적 조건부 UPDATE** 로 진화했다(낙관적 락 재시도 루프 아님). 파일명은
> 외부 링크 안정성을 위해 유지한다.

## 컨텍스트

색상·사이즈 등 옵션 조합 1 개 = SKU 1 개다. 옵션 상품의 재고는 `Product` 가 아닌
`ProductVariant`(SKU) 가 소유하며, 주문·결제는 productId 가 아닌 variantId 기준으로 동작한다.

핫딜·선착순처럼 같은 SKU 에 동시 차감이 폭주하면 **초과판매(oversell)** 위험이 생긴다.
"읽고-검증하고-쓰는"(read-modify-write) 패턴은 두 트랜잭션이 같은 재고를 읽고 각자 차감하면
재고가 음수가 된다. 도메인 가드(`ProductVariant.decreaseStock`)는 단건 검증용이지, 고동시성
경합 자체를 막지는 못한다.

초기에는 `@Version`(V36 마이그레이션) 기반 **낙관적 락 + 백오프 재시도**를 고려했다. 그러나
경합이 심한 핫 경로에서 낙관적 락은 충돌 시 재시도가 누적되어 **재시도 한계 실패**가 발생하고,
재시도 루프의 복잡도·지연이 커진다.

## 결정

재고 차감 핫 경로는 **단일 원자적 조건부 UPDATE** 한 문장으로 "재고 검증 + 차감 + 매진 전이"
를 DB row 락 안에서 처리한다. 낙관적 락 재시도 루프는 쓰지 않는다.

### 1. 조건부 UPDATE (`decreaseStockIfAvailable`)

`SpringDataProductVariantRepository.decreaseStockIfAvailable` 의 JPQL:

```sql
UPDATE ProductVariantJpaEntity v
   SET v.stockQuantity = v.stockQuantity - :qty,
       v.status = CASE WHEN v.stockQuantity - :qty = 0 THEN OUT_OF_STOCK ELSE v.status END,
       v.version = v.version + 1,
       v.updatedAt = :now
 WHERE v.id = :id
   AND v.stockQuantity >= :qty
   AND v.status <> DISCONTINUED
```

`WHERE stock >= :qty` 가 row 락 안에서 평가되므로, 차감이 폭주해도 락 대기·낙관적 충돌 재시도
없이 정확히 보유 수량만큼만 성공한다(초과판매 방지). `@Modifying(clearAutomatically=true)` 로
1차 캐시 stale 을 방지한다.

### 2. 영향 행 수로 실패 분류

`SaveProductVariantPort.decreaseStockIfAvailable` 의 계약: **1 = 성공, 0 = 차감 불가**.
경합 실패는 발생하지 않는다. `DecreaseVariantStockService` 가 영향 행 0 일 때 원인을 분류한다 —
재고 부족 → `InsufficientStockException`, 단종 → `IllegalStateException`. 메트릭은
`variant.stock.decrease.success` / `variant.stock.decrease.rejected`.

### 3. @Version 의 역할

`ProductVariant.version`(`@Version`, V36)은 **남겨 두되 핫 차감 경로에서는 락 메커니즘으로
쓰지 않는다.** 옵션명·가격 변경 같은 일반 부분 수정의 lost update 방지용으로만 동작한다.
조건부 UPDATE 는 bulk JPQL 이라 `@Version` 을 자동 증가시키지 않으므로 문장에서 명시적으로 +1 한다.

## 결과

### 좋아지는 점
- 초과판매(음수 재고) 원천 차단 — DB 가 단일 진실
- 충돌 재시도 루프 없음 → 재시도 한계 실패·지연 누적 제거
- 100 스레드 동시 차감 → **50 성공 / 50 InsufficientStock / 최종 재고 0 / 음수 없음**
  (`VariantStockConcurrencyIT` 검증)

### 트레이드오프 / 리스크
- 차감 로직이 도메인 객체가 아닌 영속 쿼리에 위치 — 단건 검증 가드와 이원화
- 부분 차감·복합 조건 확장 시 JPQL 표현력 한계
- 잔존 `@Version` 컬럼의 의미(핫 경로 비사용)를 코드/문서로 명확히 유지해야 오해 방지

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **원자적 조건부 UPDATE (본 결정)** | ✓ | 초과판매 0, 재시도 루프 0, 단일 라운드트립 |
| 낙관적 락(@Version) + 백오프 재시도 | ✗ | 핫 경로에서 재시도 한계 실패·복잡도·지연 |
| 비관적 락(SELECT ... FOR UPDATE 후 차감) | △ | 정확하나 락 보유 시간↑·왕복 2회 — 핫 경로 처리량 저하 |
| 애플리케이션 메모리 카운터 | ✗ | 멀티 인스턴스 비일관·재기동 유실 |

## 참조

- [0013 — 분할 결제(Tenders) + 역환불](0013-split-payment-with-tenders.md)
