package github.lms.lemuel.menu.application.port.in;

import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuAttributes;

import java.util.List;

public interface MenuUseCase {

    /** 전체 메뉴를 트리 구조로 반환 (sort_order 정렬). 관리 화면용 — 필터링하지 않는다. */
    List<Menu> getMenuTree();

    /**
     * 호출자에게 보여 줄 메뉴만 남긴 트리를 반환한다 (셸 네비게이션용).
     * 숨김·비활성 제외, 역할·권한 미달 제외, 자식이 하나도 남지 않은 묶음은 가지치기.
     *
     * @param role 호출자 역할 코드. 미인증이면 null.
     */
    List<Menu> getVisibleMenuTreeForRole(String role);

    /** 평면 목록 반환 (부모 선택용) */
    List<Menu> getAllFlat();

    /** 메뉴 생성 */
    Menu createMenu(CreateMenuCommand command);

    /** 메뉴 수정 */
    Menu updateMenu(Long id, UpdateMenuCommand command);

    /** 메뉴 삭제 (자식 있으면 거부) */
    void deleteMenu(Long id);

    /**
     * 배치 재배치 — 여러 메뉴의 부모/정렬순서를 한 번에 저장한다.
     * 순환 참조·깊이 초과·영역 불일치가 발생하는 재배치는 전체 거부된다.
     *
     * @return 변경 저장된 메뉴 목록
     */
    List<Menu> reorder(List<ReorderItemCommand> items);

    record ReorderItemCommand(
            Long id,
            Long parentId,
            int sortOrder
    ) {}

    record CreateMenuCommand(
            MenuAttributes attributes,
            Long parentId,
            int sortOrder,
            boolean visible
    ) {}

    record UpdateMenuCommand(
            MenuAttributes attributes,
            Long parentId,
            int sortOrder,
            boolean visible,
            boolean active
    ) {}
}
