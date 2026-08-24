package github.lms.lemuel.product.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataProductVariantRepository extends JpaRepository<ProductVariantJpaEntity, Long> {

    Optional<ProductVariantJpaEntity> findBySku(String sku);

    List<ProductVariantJpaEntity> findByProductId(Long productId);

    /**
     * 조합 서명으로 SKU 단건 조회 — {@code uq_product_variants_signature} 유니크 인덱스를 그대로 탄다.
     * 문자열 option_name 전량 스캔을 대체하는 경로다.
     */
    Optional<ProductVariantJpaEntity> findByProductIdAndOptionSignature(Long productId, String optionSignature);

    /** SKU 를 하나라도 가진 상품 id (옵션 카탈로그 백필의 순회 대상). */
    @Query("SELECT DISTINCT v.productId FROM ProductVariantJpaEntity v ORDER BY v.productId ASC")
    List<Long> findDistinctProductIds();

    /**
     * 재고 원자적 차감 — 단일 조건부 UPDATE 로 "재고 검증 + 차감 + 매진 전이" 를 한 번에 처리한다.
     *
     * <p>{@code WHERE stock >= :qty} 조건이 DB row 락 안에서 평가되므로, 선착순/핫딜로 같은 SKU 에
     * 차감이 폭주해도 락 대기·낙관적 충돌 재시도 없이 정확히 보유 수량만큼만 성공시킨다(초과판매 방지).
     * 영향 행이 0 이면 재고 부족·단종·미존재 중 하나이며, 충돌(경합) 실패는 발생하지 않는다.
     *
     * <p>bulk JPQL UPDATE 는 {@code @Version} 을 자동 증가시키지 않으므로 명시적으로 +1 한다.
     * 1 차 캐시 stale 방지를 위해 {@code clearAutomatically=true}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProductVariantJpaEntity v " +
           "SET v.stockQuantity = v.stockQuantity - :qty, " +
           "    v.status = CASE WHEN v.stockQuantity - :qty = 0 " +
           "                    THEN github.lms.lemuel.product.domain.ProductVariantStatus.OUT_OF_STOCK " +
           "                    ELSE v.status END, " +
           "    v.version = v.version + 1, " +
           "    v.updatedAt = :now " +
           "WHERE v.id = :id " +
           "  AND v.stockQuantity >= :qty " +
           "  AND v.status <> github.lms.lemuel.product.domain.ProductVariantStatus.DISCONTINUED")
    int decreaseStockIfAvailable(@Param("id") Long id, @Param("qty") int qty, @Param("now") LocalDateTime now);

    /**
     * 옵션(SKU) 재고 원자적 원복(증가) — 환불/취소 시 차감했던 SKU 재고를 되돌린다.
     *
     * <p>차감의 역연산: {@code stock = stock + qty}. 품절(OUT_OF_STOCK)이었던 SKU 는 ACTIVE 로 되살린다.
     * 단종(DISCONTINUED)은 부활시키지 않는다(영향 행 0 → 원복 스킵).
     * bulk JPQL UPDATE 는 {@code @Version} 을 자동 증가시키지 않으므로 명시적으로 +1 한다.
     *
     * <p>{@code flushAutomatically=true}: 벌크 UPDATE 전에 대기 중인 영속성 변경(예: 같은 트랜잭션의
     * 주문 상태 전이)을 flush 해, 이어지는 {@code clearAutomatically} 로 유실되지 않게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ProductVariantJpaEntity v " +
           "SET v.stockQuantity = v.stockQuantity + :qty, " +
           "    v.status = CASE WHEN v.status = github.lms.lemuel.product.domain.ProductVariantStatus.OUT_OF_STOCK " +
           "                    THEN github.lms.lemuel.product.domain.ProductVariantStatus.ACTIVE " +
           "                    ELSE v.status END, " +
           "    v.version = v.version + 1, " +
           "    v.updatedAt = :now " +
           "WHERE v.id = :id " +
           "  AND v.status <> github.lms.lemuel.product.domain.ProductVariantStatus.DISCONTINUED")
    int increaseStock(@Param("id") Long id, @Param("qty") int qty, @Param("now") LocalDateTime now);
}
