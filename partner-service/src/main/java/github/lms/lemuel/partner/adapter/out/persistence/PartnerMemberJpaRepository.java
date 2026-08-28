package github.lms.lemuel.partner.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** {@code partner_members} 적재 + 로그인 사용자 → 소속 조직 해석. */
interface PartnerMemberJpaRepository extends JpaRepository<PartnerMemberJpaEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO partner.partner_members
                (membership_id, organization_id, user_id, role, status, joined_at, updated_at)
            VALUES
                (:membershipId, :organizationId, :userId, :role, 'ACTIVE', NOW(), NOW())
            ON CONFLICT (membership_id) DO UPDATE SET
                organization_id = EXCLUDED.organization_id,
                user_id         = EXCLUDED.user_id,
                role            = EXCLUDED.role,
                status          = 'ACTIVE',
                updated_at      = NOW()
            """, nativeQuery = true)
    void upsert(@Param("membershipId") long membershipId,
                @Param("organizationId") long organizationId,
                @Param("userId") long userId,
                @Param("role") String role);

    /**
     * 탈퇴. 행을 지우지 않고 상태만 바꾸는 이유는 부분 유니크 인덱스
     * {@code uq_partner_members_active} 다 — 같은 사람이 나갔다 다시 들어오면 membership_id 가
     * 다른 새 행이 오는데, 옛 행을 남겨 두어도 status 로 걸러지므로 충돌하지 않는다.
     */
    @Modifying
    @Query(value = """
            UPDATE partner.partner_members
               SET status = 'REMOVED', updated_at = NOW()
             WHERE membership_id = :membershipId
            """, nativeQuery = true)
    int markRemoved(@Param("membershipId") long membershipId);

    @Modifying
    @Query(value = """
            UPDATE partner.partner_members
               SET role = :role, updated_at = NOW()
             WHERE membership_id = :membershipId
            """, nativeQuery = true)
    int changeRole(@Param("membershipId") long membershipId, @Param("role") String role);

    /**
     * 로그인 사용자가 속한 <b>활성</b> 조직들. 정렬을 못 박아 둔 것은, 한 사람이 두 조직에
     * 속했을 때 화면이 매번 다른 조직을 보여주는 것보다 <i>항상 같은 조직</i>을 보여주고
     * 어댑터가 경고를 남기는 편이 낫기 때문이다. 조직 전환 UI 는 아직 없다.
     *
     * <p>반환 순서: organization_id, name, org_type, seller_id, role
     */
    @Query(value = """
            SELECT m.organization_id, p.name, p.org_type, p.seller_id, m.role
              FROM partner.partner_members m
              JOIN partner.partners p ON p.organization_id = m.organization_id
             WHERE m.user_id = :userId
               AND m.status = 'ACTIVE'
             ORDER BY m.joined_at, m.membership_id
            """, nativeQuery = true)
    List<Object[]> findScopeRows(@Param("userId") long userId);

    /** 반환 순서: membership_id, user_id, role, joined_at */
    @Query(value = """
            SELECT m.membership_id, m.user_id, m.role, m.joined_at
              FROM partner.partner_members m
             WHERE m.organization_id = :organizationId
               AND m.status = 'ACTIVE'
             ORDER BY m.joined_at, m.membership_id
            """, nativeQuery = true)
    List<Object[]> findActiveMemberRows(@Param("organizationId") long organizationId);
}
