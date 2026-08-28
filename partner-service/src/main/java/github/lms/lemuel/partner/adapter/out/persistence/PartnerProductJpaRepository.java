package github.lms.lemuel.partner.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 상품명 적재 — 베스트 상품 화면이 ID 대신 이름을 보여주게 하는 것뿐이다. */
interface PartnerProductJpaRepository extends JpaRepository<PartnerProductJpaEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO partner.partner_products (product_id, name, updated_at)
            VALUES (:productId, :name, NOW())
            ON CONFLICT (product_id) DO UPDATE SET
                name       = EXCLUDED.name,
                updated_at = NOW()
            """, nativeQuery = true)
    void upsert(@Param("productId") long productId, @Param("name") String name);
}
