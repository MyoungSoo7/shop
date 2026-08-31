package github.lms.lemuel.seller.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * {@code seller_products} 매핑 — 주문 목록에서 상품 ID 대신 이름을 보여주고, 승인된 신청서가
 * 카탈로그에 실제로 실렸는지를 확인하는 근거다.
 */
@Entity
@Table(name = "seller_products")
class SellerProductJpaEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(length = 300)
    private String name;

    @Column(name = "submission_id")
    private Long submissionId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SellerProductJpaEntity() {
    }
}
