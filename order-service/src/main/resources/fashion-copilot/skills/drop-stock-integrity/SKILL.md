---
name: drop-stock-integrity
description: 한정판 드랍 재고 동시성 규칙 — 원자적 조건부 UPDATE, 오버셀 방지, 품절 시그널. 재고 차감/복원/드랍 코드를 작성하거나 품절·오버셀을 조사할 때 로드.
---

# Drop Stock Integrity (한정판 드랍 재고 정합성)

드랍(한정판 라이브 발매) 순간에는 같은 variant 에 동시 주문이 몰린다.
재고 1개를 2명에게 팔면(오버셀) 브랜드 신뢰 사고 — 이 코드베이스의 동시성 전략을 그대로 지켜라.

## 핵심 패턴 — 원자적 조건부 UPDATE (락 대기 0)

`SpringDataProductVariantRepository.decreaseStockIfAvailable`:

```sql
UPDATE product_variants
   SET stock_quantity = stock_quantity - :qty,
       status = CASE WHEN stock_quantity - :qty = 0 THEN 'OUT_OF_STOCK' ELSE status END,
       version = version + 1
 WHERE id = :id AND stock_quantity >= :qty AND status <> 'DISCONTINUED'
```

- **낙관/비관 락이 아니다** — 조건부 UPDATE 한 방이 전부다. 락 대기·재시도 루프가 없어서
  드랍 트래픽에서도 커넥션이 밀리지 않는다.
- 영향 행 0 = 차감 실패 → `classifyFailure` 로 원인(재고 부족/단종/미존재)을 분류한다.
- `@Modifying(clearAutomatically = true)` — 영속성 컨텍스트 stale 방지. 새 재고 쿼리에도 붙여라.

## 절대 규칙

1. **read-modify-write 금지**: `variant.getStockQuantity()` 로 읽고 빼서 `setStockQuantity()` 로
   저장하는 코드는 어떤 이유로도 만들지 마라 — 동시 요청 사이에서 차감이 유실된다(오버셀).
2. **재고 진실 원천은 variant**: `products.options_json` 은 옵션 트리 원본일 뿐이다.
   재고 판단·차감을 options_json 파싱으로 구현하지 마라. (옵션 없는 상품만 `products.stock_quantity`.)
3. **복원 규칙**: `increaseStock` 은 OUT_OF_STOCK → ACTIVE 부활을 포함하되 **DISCONTINUED 는
   부활 금지**. 반품 재입고 코드를 새로 만들면 이 규칙을 재사용하라.
4. **트랜잭션 진입점**: 결제 트랜잭션 안에서 재고를 차감할 때는 `decreaseInNewTransaction`
   (REQUIRES_NEW) — 결제 롤백과 재고 차감의 커밋 경계를 분리한 설계 의도를 유지하라.
5. **품절 시그널**: 재고 부족 분기에서만 `STOCK_DEPLETED` ops signal
   (`lemuel.ops.stock.depleted`) 을 emit 한다 — best-effort·절대 throw 금지(Outbox 미경유가 의도).

## 관측 (드랍 중 판정 기준)

Micrometer 카운터: `variant.stock.decrease.success|rejected`, `product.stock.decrease.success|rejected`

- 드랍 중 rejected 급증 = **품절 러시(정상 방어 동작)** — 재고 소진 후 거절이 쌓이는 것.
- 평시 rejected 지속 발생 = **재고 노출 버그 의심** — 품절 상품이 구매 가능으로 노출되는 중.
- MCP `stock_pulse()` 가 두 축의 성공/거절 비율을 요약한다.

## 드랍 전 체크리스트

1. 대상 variant 의 `stock_quantity`·status 확정 (DISCONTINUED 섞임 없는지)
2. 재고 노출 캐시 TTL 확인 — 품절 후에도 구매 버튼이 살아 있으면 rejected 만 쌓인다
3. `stock_pulse` 베이스라인 채집 (드랍 중 비교 기준)
4. `lemuel.ops.stock.depleted` 컨슈머(operation-service) 정상 여부
