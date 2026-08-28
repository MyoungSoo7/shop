package github.lms.lemuel.partner.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** {@code partner_members} 매핑 — PK 가 membership_id 인 이유는 V1 마이그레이션 주석 참조. */
@Entity
@Table(name = "partner_members")
class PartnerMemberJpaEntity {

    @Id
    @Column(name = "membership_id")
    private Long membershipId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PartnerMemberJpaEntity() {
    }
}
