package github.lms.lemuel.shipping.domain;

/** 안심번호 풀 항목의 상태. 배정과 회수 두 상태뿐이라 전이표 대신 도메인 메서드가 직접 강제한다. */
public enum SafetyNumberStatus {
    /** 풀에 대기 중 — 다음 배정 후보. */
    AVAILABLE,
    /** 특정 주문에 배정됨 — 만료 전까지 다른 주문이 쓸 수 없다. */
    ASSIGNED
}
