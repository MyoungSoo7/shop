package github.lms.lemuel.point.domain;

/**
 * 적립률 정책의 적용 범위.
 *
 * <p>가장 구체적인 계약이 이긴다 — {@code CATEGORY > GRADE > GLOBAL}. ADR 0032 의
 * {@code SELLER > TIER} 우선순위와 같은 원칙이며, 해석 결과가 항상 유일해진다.
 *
 * <p>Phase 1 에서 실제로 쓰는 것은 {@link #GLOBAL} 뿐이다. 나머지 둘은 회원 등급·카테고리
 * 프로모션이 붙을 때를 위한 자리이며, 스키마와 우선순위를 미리 정해 두면 나중에
 * 데이터만 넣어도 동작한다.
 */
public enum PointEarnScope {
    GLOBAL(1),
    GRADE(2),
    CATEGORY(3);

    private final int priority;

    PointEarnScope(int priority) {
        this.priority = priority;
    }

    /** 클수록 구체적이고, 구체적인 쪽이 이긴다. */
    public int priority() {
        return priority;
    }
}
