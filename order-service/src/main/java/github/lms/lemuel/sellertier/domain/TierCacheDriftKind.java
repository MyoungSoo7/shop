package github.lms.lemuel.sellertier.domain;

/** 등급 캐시 드리프트의 종류 — 복구 방법이 달라서 나눈다 (ADR 0031). */
public enum TierCacheDriftKind {

    /** 캐시가 정본과 다른 값 — 정본으로 덮어쓰면 된다. */
    CACHE_STALE,

    /** 정본은 있는데 캐시가 비었다 — 동기화가 한 번도 닿지 않은 셀러. */
    CACHE_MISSING,

    /** 캐시만 있고 정본이 없다 — 정본 도입 전 수기 UPDATE 의 흔적. 먼저 정본을 만들어야 한다. */
    AUTHORITY_MISSING
}
