package github.lms.lemuel.menu.domain;

/**
 * 메뉴의 표시·접근 속성 묶음.
 *
 * <p>필드가 늘 때마다 팩토리 인자를 늘리면 호출부가 "위치로 의미를 세는" 코드가 된다. 표시(무엇을
 * 어떻게 보여주는가)와 접근(누구에게 보여주는가)은 함께 검증돼야 하므로 한 덩어리로 묶는다.
 *
 * @param name             메뉴 이름. 사이드바 머리글·목록에 그대로 쓰인다.
 * @param shortName        상단 네비용 짧은 이름(null 이면 {@code name} 사용).
 * @param path             착지 경로. {@link MenuType#DIVIDER} 는 반드시 null.
 * @param icon             아이콘(이모지 등).
 * @param description      부제 — 사이드바 항목 아래 한 줄 설명.
 * @param area             소속 영역.
 * @param type             노드 종류.
 * @param requiredRole     접근 허용 역할 CSV allowlist(예: {@code "ADMIN,MANAGER"}). null 이면 역할 제한 없음.
 * @param requiredPermission 접근 필요 권한 코드(permissions.code). null 이면 권한 제한 없음.
 */
public record MenuAttributes(
        String name,
        String shortName,
        String path,
        String icon,
        String description,
        MenuArea area,
        MenuType type,
        String requiredRole,
        String requiredPermission
) {

    /** 링크 한 줄짜리 최소 생성 — 테스트·간단한 항목용. */
    public static MenuAttributes item(String name, String path, MenuArea area, String requiredRole) {
        return new MenuAttributes(name, null, path, null, null, area, MenuType.ITEM, requiredRole, null);
    }
}
