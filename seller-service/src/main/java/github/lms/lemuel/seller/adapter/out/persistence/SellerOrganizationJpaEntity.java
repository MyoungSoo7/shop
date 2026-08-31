package github.lms.lemuel.seller.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * {@code seller_organizations} 매핑.
 *
 * <p>이 엔티티로 쓰지는 않는다 — 적재는 전부 {@code ON CONFLICT} 네이티브 upsert 다. 그래도
 * 엔티티를 두는 이유는 {@code ddl-auto: validate} 다. 마이그레이션과 코드가 어긋나면 <b>기동
 * 시점에</b> 터지고, 그게 없으면 첫 조회가 런타임 SQL 오류로 터진다(사용자가 먼저 본다).
 */
@Entity
@Table(name = "seller_organizations")
class SellerOrganizationJpaEntity {

    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "org_type", nullable = false, length = 20)
    private String orgType;

    @Column(name = "external_ref", length = 100)
    private String externalRef;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SellerOrganizationJpaEntity() {
    }
}
