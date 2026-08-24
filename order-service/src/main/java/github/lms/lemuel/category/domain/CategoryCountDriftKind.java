package github.lms.lemuel.category.domain;

/**
 * 상품수 캐시가 어긋난 방향. 방향마다 의심할 경로가 다르다.
 */
public enum CategoryCountDriftKind {

    /** 캐시가 실계수보다 크다 — 매핑을 지운 뒤 갱신이 빠진 쪽을 의심한다. 뱃지가 부풀어 보인다. */
    OVERCOUNT,

    /** 캐시가 실계수보다 작다 — 매핑을 새로 넣은 뒤 갱신이 빠진 쪽. 새 상품이 트리에서 안 보인다. */
    UNDERCOUNT
}
