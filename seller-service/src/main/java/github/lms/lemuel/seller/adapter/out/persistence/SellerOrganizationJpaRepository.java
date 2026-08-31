package github.lms.lemuel.seller.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code seller_organizations} 프로젝션 적재.
 *
 * <p>네이티브 SQL 에 {@code seller.} 스키마를 <b>직접 박는다.</b> {@code hibernate.default_schema}
 * 는 엔티티 매핑에만 적용되고 네이티브 쿼리에는 적용되지 않는다 — 형제 모듈(partner) 도 같은
 * 이유로 스키마를 박아 두었다. 빼면 search_path 에 기대게 되고, 커넥션 풀 설정 한 줄로 조용히
 * 다른 스키마를 때린다.
 */
interface SellerOrganizationJpaRepository extends JpaRepository<SellerOrganizationJpaEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO seller.seller_organizations
                (organization_id, name, org_type, external_ref, seller_id, owner_user_id,
                 created_at, updated_at)
            VALUES
                (:organizationId, :name, :orgType, :externalRef, :sellerId, :ownerUserId,
                 NOW(), NOW())
            ON CONFLICT (organization_id) DO UPDATE SET
                name          = EXCLUDED.name,
                org_type      = EXCLUDED.org_type,
                external_ref  = EXCLUDED.external_ref,
                seller_id     = COALESCE(EXCLUDED.seller_id, seller.seller_organizations.seller_id),
                owner_user_id = EXCLUDED.owner_user_id,
                updated_at    = NOW()
            """, nativeQuery = true)
    void upsert(@Param("organizationId") long organizationId,
                @Param("name") String name,
                @Param("orgType") String orgType,
                @Param("externalRef") String externalRef,
                @Param("sellerId") Long sellerId,
                @Param("ownerUserId") long ownerUserId);
}
