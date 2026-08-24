package github.lms.lemuel.menu.domain;

/**
 * 메뉴 노드 종류 — 렌더러가 이 값으로 그리는 모양을 정한다.
 */
public enum MenuType {
    /** 하위 메뉴를 거느리는 묶음. path 는 묶음을 눌렀을 때 착지할 대표 경로다. */
    GROUP,
    /** 단일 링크. */
    ITEM,
    /** 구분선 — 링크가 아니므로 path 를 갖지 않는다. */
    DIVIDER;

    /** 링크로 동작하는가(= path 가 반드시 있어야 하는가). */
    public boolean requiresPath() {
        return this != DIVIDER;
    }
}
