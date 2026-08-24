package github.lms.lemuel.organization.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 조직-멤버 연결 애그리게잇 (순수 POJO) — user 의 조직 소속·역할·가입 라이프사이클.
 *
 * <p>상태 전이는 {@link MembershipStatus} 상태머신이 강제한다. 마지막 OWNER 불변식은 여러 멤버십을
 * 가로지르므로 애플리케이션 서비스가 {@link LastOwnerException} 으로 강제한다(단일 멤버십은 알 수 없음).
 */
public class Membership {

    private final Long id;
    private final Long organizationId;
    private final Long userId;              // 비검증 비즈니스 키
    private OrgRole role;
    private MembershipStatus status;
    private final Long invitedBy;           // 초대 주체 userId (생성자 자동 OWNER 는 self)
    private final Instant createdAt;
    private final long version;

    private Membership(Builder b) {
        this.id = b.id;
        this.organizationId = Objects.requireNonNull(b.organizationId, "organizationId");
        this.userId = Objects.requireNonNull(b.userId, "userId");
        this.role = Objects.requireNonNull(b.role, "role");
        this.status = Objects.requireNonNull(b.status, "status");
        this.invitedBy = b.invitedBy;
        this.createdAt = b.createdAt;
        this.version = b.version;
    }

    /** 조직 생성자의 자동 OWNER 멤버십 — 즉시 ACTIVE. */
    public static Membership owner(Long organizationId, Long userId) {
        return builder()
                .organizationId(organizationId)
                .userId(userId)
                .role(OrgRole.OWNER)
                .status(MembershipStatus.ACTIVE)
                .invitedBy(userId)
                .build();
    }

    /** 초대 — INVITED 로 생성. 수락 시 ACTIVE 로 전이한다. */
    public static Membership invite(Long organizationId, Long userId, OrgRole role, Long invitedBy) {
        return builder()
                .organizationId(organizationId)
                .userId(userId)
                .role(role)
                .status(MembershipStatus.INVITED)
                .invitedBy(invitedBy)
                .build();
    }

    /** 초대 수락 — INVITED → ACTIVE. */
    public void accept() {
        transitionTo(MembershipStatus.ACTIVE);
    }

    public void suspend() {
        transitionTo(MembershipStatus.SUSPENDED);
    }

    public void reactivate() {
        transitionTo(MembershipStatus.ACTIVE);
    }

    /** 제거 — 활성 슬롯을 비워 재초대가 가능해진다. */
    public void remove() {
        transitionTo(MembershipStatus.REMOVED);
    }

    /** 역할 변경 — 종료된(REMOVED) 멤버십은 변경 불가. 마지막 OWNER 보호는 서비스가 선검증. */
    public void changeRole(OrgRole newRole) {
        if (status == MembershipStatus.REMOVED) {
            throw new InvalidMembershipTransitionException(status, status);
        }
        this.role = Objects.requireNonNull(newRole, "newRole");
    }

    private void transitionTo(MembershipStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidMembershipTransitionException(status, target);
        }
        this.status = target;
    }

    /** 조직의 OWNER 정족수 판정에 쓰이는 술어 — 활성이면서 OWNER 인 멤버만 카운트한다. */
    public boolean isActiveOwner() {
        return status == MembershipStatus.ACTIVE && role == OrgRole.OWNER;
    }

    public boolean occupiesActiveSlot() {
        return status.occupiesActiveSlot();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getUserId() {
        return userId;
    }

    public OrgRole getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Long getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private Long organizationId;
        private Long userId;
        private OrgRole role;
        private MembershipStatus status;
        private Long invitedBy;
        private Instant createdAt;
        private long version;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder organizationId(Long v) {
            this.organizationId = v;
            return this;
        }

        public Builder userId(Long v) {
            this.userId = v;
            return this;
        }

        public Builder role(OrgRole v) {
            this.role = v;
            return this;
        }

        public Builder status(MembershipStatus v) {
            this.status = v;
            return this;
        }

        public Builder invitedBy(Long v) {
            this.invitedBy = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Builder version(long v) {
            this.version = v;
            return this;
        }

        public Membership build() {
            return new Membership(this);
        }
    }
}
