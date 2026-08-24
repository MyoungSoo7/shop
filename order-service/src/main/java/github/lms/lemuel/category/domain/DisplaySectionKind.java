package github.lms.lemuel.category.domain;

/**
 * 진열 편성의 종류.
 *
 * <p>기획전을 분류 트리로 복제하지 않고 이 한 축으로 구분한다 — 트리 로직이 두 벌이 되면
 * 순환 검사·깊이 제한 같은 규칙이 한쪽에서만 고쳐지는 사고가 난다.
 */
public enum DisplaySectionKind {

    /** 메인 화면 진열. */
    MAIN,

    /** 기간이 있는 기획전. */
    EXHIBITION,

    /** 특정 카테고리의 베스트 — 이 종류만 카테고리를 가리킨다. */
    CATEGORY_BEST;

    /** 이 종류가 카테고리를 지목해야 하는가. */
    public boolean requiresCategory() {
        return this == CATEGORY_BEST;
    }
}
