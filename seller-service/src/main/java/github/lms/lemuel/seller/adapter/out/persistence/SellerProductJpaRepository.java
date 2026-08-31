package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 상품명 적재와, 승인된 신청서 ↔ 카탈로그 상품의 연결. */
interface SellerProductJpaRepository extends JpaRepository<SellerProductJpaEntity, Long> {

    /**
     * {@code product.changed} 적재.
     *
     * <p>{@code submission_id} 를 갱신 목록에서 <b>일부러 뺐다.</b> 이 이벤트는 신청서를 모르므로
     * 여기서 건드리면 이미 연결해 둔 신청서가 상품 이름 한 번 바뀔 때마다 끊긴다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO seller.seller_products (product_id, name, updated_at)
            VALUES (:productId, :name, NOW())
            ON CONFLICT (product_id) DO UPDATE SET
                name       = EXCLUDED.name,
                updated_at = NOW()
            """, nativeQuery = true)
    void upsert(@Param("productId") long productId, @Param("name") String name);

    /**
     * {@code product.registered} 적재 — 우리가 낸 등록 요청의 회신이다.
     *
     * <p>여기가 <b>사본</b>이라는 점이 중요하다. 신청서 원본의 {@code product_id} 는 애그리거트를
     * 통해 따로 찍는다(SellerProjectionService 참조). 이 테이블만 다시 만들어도 원본이 상하지
     * 않게 하려는 것이다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO seller.seller_products (product_id, name, submission_id, updated_at)
            VALUES (:productId, :name, :submissionId, NOW())
            ON CONFLICT (product_id) DO UPDATE SET
                name          = COALESCE(EXCLUDED.name, seller.seller_products.name),
                submission_id = EXCLUDED.submission_id,
                updated_at    = NOW()
            """, nativeQuery = true)
    void linkProduct(@Param("productId") long productId,
                     @Param("name") String name,
                     @Param("submissionId") long submissionId);
}
