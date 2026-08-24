package github.lms.lemuel.organization.domain;

/**
 * 조직 내 역할 — 권한 판정의 기준. OWNER &gt; MANAGER &gt; STAFF.
 *
 * <p>인가는 요청 파라미터가 아니라 JWT 주체(userId)의 조직 내 역할로 판정한다(IDOR 방지).
 */
public enum OrgRole {
    OWNER(3),
    MANAGER(2),
    STAFF(1);

    private final int rank;

    OrgRole(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(OrgRole other) {
        return this.rank >= other.rank;
    }

    /** 멤버 초대 권한 — OWNER/MANAGER. */
    public boolean canInviteMembers() {
        return this == OWNER || this == MANAGER;
    }

    /** 멤버 역할 변경·제거 권한 — OWNER 전용. */
    public boolean canManageMembers() {
        return this == OWNER;
    }
}
