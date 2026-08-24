package github.lms.lemuel.organization.domain;

/**
 * 멤버십 라이프사이클 상태머신.
 *
 * <pre>
 * INVITED ──accept──→ ACTIVE ⇄ SUSPENDED
 *   │                   │           │
 *   └──reject/revoke──→ REMOVED ←───┴─── (터미널)
 * </pre>
 *
 * <p>REMOVED 는 터미널. {@link #occupiesActiveSlot()}(INVITED|ACTIVE)이 uq_membership_active 인덱스의
 * 상태 집합과 일치해야 한다 — 한쪽만 바뀌면 활성 유일성이 깨진다.
 */
public enum MembershipStatus {
    INVITED,
    ACTIVE,
    SUSPENDED,
    REMOVED;

    public boolean canTransitionTo(MembershipStatus target) {
        return switch (this) {
            case INVITED -> target == ACTIVE || target == REMOVED;
            case ACTIVE -> target == SUSPENDED || target == REMOVED;
            case SUSPENDED -> target == ACTIVE || target == REMOVED;
            case REMOVED -> false;
        };
    }

    /** 활성 슬롯 점유 여부 — 초대 대기(INVITED)와 참여(ACTIVE)는 (org,user) 당 1건만 허용된다. */
    public boolean occupiesActiveSlot() {
        return this == INVITED || this == ACTIVE;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
