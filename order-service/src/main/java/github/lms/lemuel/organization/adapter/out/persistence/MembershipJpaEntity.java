package github.lms.lemuel.organization.adapter.out.persistence;

import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.MembershipStatus;
import github.lms.lemuel.organization.domain.OrgRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** memberships 테이블 매핑 (V1). detached merge + @Version 낙관적 락(OrganizationJpaEntity 와 동일 관례). */
@Entity
@Table(name = "memberships")
public class MembershipJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrgRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @Column(name = "invited_by")
    private Long invitedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MembershipJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static MembershipJpaEntity fromDomain(Membership m) {
        MembershipJpaEntity e = new MembershipJpaEntity();
        e.id = m.getId();
        e.organizationId = m.getOrganizationId();
        e.userId = m.getUserId();
        e.role = m.getRole();
        e.status = m.getStatus();
        e.invitedBy = m.getInvitedBy();
        e.version = m.getVersion();
        return e;
    }

    public Membership toDomain() {
        return Membership.builder()
                .id(id)
                .organizationId(organizationId)
                .userId(userId)
                .role(role)
                .status(status)
                .invitedBy(invitedBy)
                .createdAt(createdAt)
                .version(version)
                .build();
    }

    public Long getId() {
        return id;
    }
}
