package github.lms.lemuel.partner.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** {@code partner_products} 매핑 — 베스트 상품 화면에서 ID 대신 이름을 보여주기 위한 것뿐이다. */
@Entity
@Table(name = "partner_products")
class PartnerProductJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(length = 300)
    private String name;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PartnerProductJpaEntity() {
    }
}
