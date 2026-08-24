package github.lms.lemuel.menu.application.service;

import github.lms.lemuel.menu.application.port.in.MenuUseCase;
import github.lms.lemuel.menu.application.port.out.LoadMenuPort;
import github.lms.lemuel.menu.application.port.out.LoadPermissionCodesPort;
import github.lms.lemuel.menu.application.port.out.SaveMenuPort;
import github.lms.lemuel.menu.domain.Menu;
import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuAttributes;
import github.lms.lemuel.menu.domain.MenuType;
import github.lms.lemuel.menu.domain.exception.MenuInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock LoadMenuPort loadMenuPort;
    @Mock SaveMenuPort saveMenuPort;
    @Mock LoadPermissionCodesPort loadPermissionCodesPort;
    @InjectMocks MenuService service;

    private static MenuAttributes attrs(String name) {
        return MenuAttributes.item(name, "/" + name, MenuArea.BACKOFFICE, "USER");
    }

    private Menu menu(Long id, Long parentId, String name, int sortOrder) {
        Menu m = Menu.create(attrs(name), parentId, sortOrder, true);
        m.assignId(id);
        return m;
    }

    private Menu node(Long id, Long parentId, String name, MenuArea area,
                      MenuType type, String roles, String permission, boolean visible) {
        Menu m = Menu.create(new MenuAttributes(name, null,
                type == MenuType.DIVIDER ? null : "/" + name,
                null, null, area, type, roles, permission), parentId, 0, visible);
        m.assignId(id);
        return m;
    }

    @Test @DisplayName("getMenuTree - 부모/자식을 조립하고 sortOrder 로 정렬")
    void getMenuTree_buildsAndSorts() {
        Menu root1 = menu(1L, null, "root1", 2);
        Menu root2 = menu(2L, null, "root2", 1);
        Menu child = menu(3L, 1L, "child", 0);
        Menu orphan = menu(4L, 99L, "orphan", 5); // 부모 없음 → 루트로 처리
        when(loadMenuPort.findAll()).thenReturn(List.of(root1, root2, child, orphan));

        List<Menu> tree = service.getMenuTree();

        // root2(1) < root1(2) < orphan(5) 순서
        assertThat(tree).extracting(Menu::getId).containsExactly(2L, 1L, 4L);
        assertThat(root1.getChildren()).extracting(Menu::getId).containsExactly(3L);
    }

    @Test @DisplayName("getAllFlat - 포트에 위임")
    void getAllFlat() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));
        assertThat(service.getAllFlat()).hasSize(1);
    }

    @Test @DisplayName("createMenu - 도메인 생성 후 저장")
    void createMenu() {
        when(saveMenuPort.save(any())).thenAnswer(inv -> {
            Menu m = inv.getArgument(0);
            m.assignId(10L);
            return m;
        });

        Menu saved = service.createMenu(new MenuUseCase.CreateMenuCommand(
                MenuAttributes.item("메뉴A", "/a", MenuArea.SYSTEM, "ADMIN"), null, 3, true));

        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getName()).isEqualTo("메뉴A");
        assertThat(saved.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(saved.getArea()).isEqualTo(MenuArea.SYSTEM);
    }

    @Test @DisplayName("createMenu - SYSTEM 메뉴는 MANAGER 역할을 거부")
    void createMenu_systemRequiresAdmin() {
        assertThatThrownBy(() -> service.createMenu(new MenuUseCase.CreateMenuCommand(
                MenuAttributes.item("메뉴 관리", "/admin/system/menus", MenuArea.SYSTEM, "MANAGER"),
                null, 0, true)))
                .isInstanceOf(MenuInvariantViolationException.class)
                .hasMessageContaining("ADMIN");
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("createMenu - 시스템 경로는 영역이 잘못되어도 MANAGER 역할을 거부")
    void createMenu_systemPathRequiresAdmin() {
        assertThatThrownBy(() -> service.createMenu(new MenuUseCase.CreateMenuCommand(
                MenuAttributes.item("메뉴 관리", "/admin/system/menus", MenuArea.BACKOFFICE, "MANAGER"),
                null, 0, true)))
                .isInstanceOf(MenuInvariantViolationException.class)
                .hasMessageContaining("ADMIN");
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 지급 메뉴는 ADMIN 외 역할을 거부")
    void updateMenu_payoutRequiresAdmin() {
        Menu existing = menu(7L, null, "지급관리", 0);
        when(loadMenuPort.findById(7L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateMenu(7L, new MenuUseCase.UpdateMenuCommand(
                MenuAttributes.item("지급관리", "/admin/settlement/payouts", MenuArea.BACKOFFICE, "ADMIN,MANAGER"),
                null, 0, true, true)))
                .isInstanceOf(MenuInvariantViolationException.class)
                .hasMessageContaining("ADMIN");
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 존재하면 수정 후 저장")
    void updateMenu_success() {
        Menu existing = menu(5L, null, "old", 0);
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(existing));
        when(saveMenuPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Menu updated = service.updateMenu(5L, new MenuUseCase.UpdateMenuCommand(
                MenuAttributes.item("new", "/new", MenuArea.BACKOFFICE, "USER"), null, 1, false, false));

        assertThat(updated.getName()).isEqualTo("new");
        assertThat(updated.isVisible()).isFalse();
        assertThat(updated.isActive()).isFalse();
    }

    @Test @DisplayName("updateMenu - 없으면 예외")
    void updateMenu_notFound() {
        when(loadMenuPort.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMenu(404L, new MenuUseCase.UpdateMenuCommand(
                attrs("n"), null, 0, true, true)))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    @Test @DisplayName("deleteMenu - 자식이 없으면 삭제")
    void deleteMenu_success() {
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(menu(5L, null, "a", 0)));
        when(loadMenuPort.existsByParentId(5L)).thenReturn(false);

        service.deleteMenu(5L);

        verify(saveMenuPort).deleteById(5L);
    }

    @Test @DisplayName("deleteMenu - 자식이 있으면 예외")
    void deleteMenu_hasChildren() {
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(menu(5L, null, "a", 0)));
        when(loadMenuPort.existsByParentId(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteMenu(5L))
                .isInstanceOf(MenuInvariantViolationException.class);
        verify(saveMenuPort, never()).deleteById(any());
    }

    @Test @DisplayName("deleteMenu - 없으면 예외")
    void deleteMenu_notFound() {
        when(loadMenuPort.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMenu(404L))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    // ── 부모 검증 (순환 참조 방지) ────────────────────────────

    @Test @DisplayName("createMenu - 존재하지 않는 부모면 예외")
    void createMenu_parentNotFound() {
        when(loadMenuPort.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.createMenu(new MenuUseCase.CreateMenuCommand(
                MenuAttributes.item("메뉴A", "/a", MenuArea.BACKOFFICE, null), 99L, 0, true)))
                .isInstanceOf(MenuInvariantViolationException.class);
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 자기 자신을 부모로 지정하면 예외")
    void updateMenu_selfParent() {
        when(loadMenuPort.findById(5L)).thenReturn(Optional.of(menu(5L, null, "a", 0)));

        assertThatThrownBy(() -> service.updateMenu(5L, new MenuUseCase.UpdateMenuCommand(
                attrs("a"), 5L, 0, true, true)))
                .isInstanceOf(MenuInvariantViolationException.class);
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 자손을 부모로 지정하면 순환 참조 예외")
    void updateMenu_descendantParent() {
        Menu root = menu(1L, null, "root", 0);
        Menu child = menu(2L, 1L, "child", 0);
        Menu grandChild = menu(3L, 2L, "grand", 0);
        when(loadMenuPort.findById(1L)).thenReturn(Optional.of(root));
        when(loadMenuPort.findAll()).thenReturn(List.of(root, child, grandChild));

        assertThatThrownBy(() -> service.updateMenu(1L, new MenuUseCase.UpdateMenuCommand(
                attrs("root"), 3L, 0, true, true)))
                .isInstanceOf(MenuInvariantViolationException.class)
                .hasMessageContaining("순환");
        verify(saveMenuPort, never()).save(any());
    }

    @Test @DisplayName("updateMenu - 존재하지 않는 부모면 예외")
    void updateMenu_parentNotFound() {
        when(loadMenuPort.findById(1L)).thenReturn(Optional.of(menu(1L, null, "a", 0)));
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.updateMenu(1L, new MenuUseCase.UpdateMenuCommand(
                attrs("a"), 99L, 0, true, true)))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    @Test @DisplayName("updateMenu - 정상 부모 변경은 저장")
    void updateMenu_validParentChange() {
        Menu a = menu(1L, null, "a", 0);
        Menu b = menu(2L, null, "b", 1);
        when(loadMenuPort.findById(1L)).thenReturn(Optional.of(a));
        when(loadMenuPort.findAll()).thenReturn(List.of(a, b));
        when(saveMenuPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Menu updated = service.updateMenu(1L, new MenuUseCase.UpdateMenuCommand(
                attrs("a"), 2L, 0, true, true));

        assertThat(updated.getParentId()).isEqualTo(2L);
    }

    // ── 배치 재배치 (reorder) ─────────────────────────────────

    @Test @DisplayName("reorder - 부모/정렬순서를 적용해 일괄 저장")
    void reorder_ok() {
        Menu a = menu(1L, null, "a", 0);
        Menu b = menu(2L, null, "b", 1);
        Menu c = menu(3L, 1L, "c", 0);
        when(loadMenuPort.findAll()).thenReturn(List.of(a, b, c));
        when(saveMenuPort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Menu> saved = service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, null, 1),
                new MenuUseCase.ReorderItemCommand(2L, null, 0),
                new MenuUseCase.ReorderItemCommand(3L, 2L, 0)   // c 를 b 아래로 이동
        ));

        assertThat(saved).hasSize(3);
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).isEqualTo(0);
        assertThat(c.getParentId()).isEqualTo(2L);
    }

    @Test @DisplayName("reorder - 빈 목록이면 저장 없이 빈 반환")
    void reorder_empty() {
        assertThat(service.reorder(List.of())).isEmpty();
        assertThat(service.reorder(null)).isEmpty();
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 존재하지 않는 메뉴 ID 면 전체 거부")
    void reorder_menuNotFound() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(99L, null, 0))))
                .isInstanceOf(MenuInvariantViolationException.class);
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 존재하지 않는 부모면 전체 거부")
    void reorder_parentNotFound() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, 99L, 0))))
                .isInstanceOf(MenuInvariantViolationException.class);
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 자기 자신을 부모로 지정하면 전체 거부")
    void reorder_selfParent() {
        when(loadMenuPort.findAll()).thenReturn(List.of(menu(1L, null, "a", 0)));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, 1L, 0))))
                .isInstanceOf(MenuInvariantViolationException.class);
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Test @DisplayName("reorder - 서로를 부모로 지정하는 순환 재배치는 전체 거부")
    void reorder_cycle() {
        Menu a = menu(1L, null, "a", 0);
        Menu b = menu(2L, 1L, "b", 0);
        when(loadMenuPort.findAll()).thenReturn(List.of(a, b));

        assertThatThrownBy(() -> service.reorder(List.of(
                new MenuUseCase.ReorderItemCommand(1L, 2L, 0)))) // b 는 이미 a 의 자식 → 순환
                .isInstanceOf(MenuInvariantViolationException.class)
                .hasMessageContaining("순환");
        verify(saveMenuPort, never()).saveAll(any());
    }

    @Nested
    @DisplayName("깊이 제한 — 셸이 그릴 수 있는 3 단계까지")
    class DepthLimit {

        @Test @DisplayName("createMenu - 4 단계가 되면 거부")
        void create_beyondMaxDepth() {
            Menu d1 = menu(1L, null, "d1", 0);
            Menu d2 = menu(2L, 1L, "d2", 0);
            Menu d3 = menu(3L, 2L, "d3", 0);
            when(loadMenuPort.findAll()).thenReturn(List.of(d1, d2, d3));

            assertThatThrownBy(() -> service.createMenu(new MenuUseCase.CreateMenuCommand(
                    MenuAttributes.item("d4", "/d4", MenuArea.BACKOFFICE, null), 3L, 0, true)))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("깊이");
            verify(saveMenuPort, never()).save(any());
        }

        @Test @DisplayName("createMenu - 3 단계는 허용")
        void create_atMaxDepth() {
            Menu d1 = menu(1L, null, "d1", 0);
            Menu d2 = menu(2L, 1L, "d2", 0);
            when(loadMenuPort.findAll()).thenReturn(List.of(d1, d2));
            when(saveMenuPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Menu saved = service.createMenu(new MenuUseCase.CreateMenuCommand(
                    MenuAttributes.item("d3", "/d3", MenuArea.BACKOFFICE, null), 2L, 0, true));

            assertThat(saved.getParentId()).isEqualTo(2L);
        }

        @Test @DisplayName("reorder - 자손까지 함께 내려가 4 단계가 되면 거부")
        void reorder_subtreeOverflow() {
            Menu a = menu(1L, null, "a", 0);
            Menu aChild = menu(2L, 1L, "aChild", 0);
            Menu aGrand = menu(3L, 2L, "aGrand", 0);
            Menu b = menu(4L, null, "b", 0);
            Menu bChild = menu(5L, 4L, "bChild", 0);
            when(loadMenuPort.findAll()).thenReturn(List.of(a, aChild, aGrand, b, bChild));

            // a(깊이1) 를 bChild(깊이2) 아래로 → a=3, aChild=4 로 초과
            assertThatThrownBy(() -> service.reorder(List.of(
                    new MenuUseCase.ReorderItemCommand(1L, 5L, 0))))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("깊이");
            verify(saveMenuPort, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("영역 일치 — 한 사이드바에 남의 영역이 섞이지 않는다")
    class AreaConsistency {

        @Test @DisplayName("createMenu - 부모와 영역이 다르면 거부")
        void create_areaMismatch() {
            Menu parent = node(1L, null, "부모", MenuArea.CEO, MenuType.GROUP, null, null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(parent));

            assertThatThrownBy(() -> service.createMenu(new MenuUseCase.CreateMenuCommand(
                    MenuAttributes.item("자식", "/c", MenuArea.SYSTEM, null), 1L, 0, true)))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("같은 영역");
            verify(saveMenuPort, never()).save(any());
        }

        @Test @DisplayName("reorder - 다른 영역 밑으로 옮기면 거부")
        void reorder_areaMismatch() {
            Menu ceo = node(1L, null, "ceo", MenuArea.CEO, MenuType.GROUP, null, null, true);
            Menu sys = node(2L, null, "sys", MenuArea.SYSTEM, MenuType.ITEM, null, null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(ceo, sys));

            assertThatThrownBy(() -> service.reorder(List.of(
                    new MenuUseCase.ReorderItemCommand(2L, 1L, 0))))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("같은 영역");
        }
    }

    @Nested
    @DisplayName("셸 네비게이션 조회 — getVisibleMenuTreeForRole")
    class VisibleTree {

        @Test @DisplayName("역할이 맞는 메뉴만 남는다")
        void filtersByRole() {
            Menu adminOnly = node(1L, null, "시스템", MenuArea.SYSTEM, MenuType.ITEM, "ADMIN", null, true);
            Menu shared = node(2L, null, "정산", MenuArea.BACKOFFICE, MenuType.ITEM, "ADMIN,MANAGER", null, true);
            Menu shop = node(3L, null, "주문", MenuArea.SHOP, MenuType.ITEM, "USER", null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(adminOnly, shared, shop));
            when(loadPermissionCodesPort.findByRoleCode("MANAGER")).thenReturn(Set.of());

            List<Menu> tree = service.getVisibleMenuTreeForRole("MANAGER");

            assertThat(tree).extracting(Menu::getName).containsExactly("정산");
        }

        @Test @DisplayName("숨김·비활성 메뉴는 빠진다")
        void filtersHidden() {
            Menu hidden = node(1L, null, "숨김", MenuArea.BACKOFFICE, MenuType.ITEM, null, null, false);
            Menu shown = node(2L, null, "노출", MenuArea.BACKOFFICE, MenuType.ITEM, null, null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(hidden, shown));

            List<Menu> tree = service.getVisibleMenuTreeForRole(null);

            assertThat(tree).extracting(Menu::getName).containsExactly("노출");
        }

        @Test @DisplayName("권한이 필요한 메뉴는 권한 보유자에게만 보인다")
        void filtersByPermission() {
            Menu gated = node(1L, null, "메뉴관리", MenuArea.SYSTEM, MenuType.ITEM,
                    "ADMIN", "SYSTEM_MENU_MANAGE", true);
            when(loadMenuPort.findAll()).thenReturn(List.of(gated));
            when(loadPermissionCodesPort.findByRoleCode("ADMIN")).thenReturn(Set.of("SYSTEM_MENU_MANAGE"));

            assertThat(service.getVisibleMenuTreeForRole("ADMIN")).hasSize(1);
        }

        @Test @DisplayName("권한이 없으면 해당 메뉴는 빠진다")
        void filtersByPermission_denied() {
            Menu gated = node(1L, null, "메뉴관리", MenuArea.SYSTEM, MenuType.ITEM,
                    "ADMIN", "SYSTEM_MENU_MANAGE", true);
            when(loadMenuPort.findAll()).thenReturn(List.of(gated));
            when(loadPermissionCodesPort.findByRoleCode("ADMIN")).thenReturn(Set.of("ORDER_READ"));

            assertThat(service.getVisibleMenuTreeForRole("ADMIN")).isEmpty();
        }

        @Test @DisplayName("자식이 전부 걸러진 묶음은 가지치기된다 — 빈 사이드바로 착지하지 않도록")
        void prunesEmptyGroup() {
            Menu group = node(1L, null, "정산", MenuArea.BACKOFFICE, MenuType.GROUP, "ADMIN,MANAGER", null, true);
            Menu adminChild = node(2L, 1L, "지급관리", MenuArea.BACKOFFICE, MenuType.ITEM, "ADMIN", null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(group, adminChild));
            when(loadPermissionCodesPort.findByRoleCode("MANAGER")).thenReturn(Set.of());

            assertThat(service.getVisibleMenuTreeForRole("MANAGER")).isEmpty();
        }

        @Test @DisplayName("자식이 하나라도 남으면 묶음은 유지된다")
        void keepsGroupWithSurvivingChild() {
            Menu group = node(1L, null, "정산", MenuArea.BACKOFFICE, MenuType.GROUP, "ADMIN,MANAGER", null, true);
            Menu shared = node(2L, 1L, "정산조회", MenuArea.BACKOFFICE, MenuType.ITEM, "ADMIN,MANAGER", null, true);
            Menu adminChild = node(3L, 1L, "지급관리", MenuArea.BACKOFFICE, MenuType.ITEM, "ADMIN", null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(group, shared, adminChild));
            when(loadPermissionCodesPort.findByRoleCode("MANAGER")).thenReturn(Set.of());

            List<Menu> tree = service.getVisibleMenuTreeForRole("MANAGER");

            assertThat(tree).hasSize(1);
            assertThat(tree.get(0).getChildren()).extracting(Menu::getName).containsExactly("정산조회");
        }

        @Test @DisplayName("자식 없는 ITEM 루트는 가지치기 대상이 아니다")
        void keepsLeafRoot() {
            Menu leaf = node(1L, null, "대시보드", MenuArea.BACKOFFICE, MenuType.ITEM, null, null, true);
            when(loadMenuPort.findAll()).thenReturn(List.of(leaf));

            assertThat(service.getVisibleMenuTreeForRole(null)).hasSize(1);
        }

        @Test @DisplayName("미인증이면 권한 조회를 하지 않는다")
        void anonymousSkipsPermissionLookup() {
            when(loadMenuPort.findAll()).thenReturn(List.of(
                    node(1L, null, "공개", MenuArea.SHOP, MenuType.ITEM, null, null, true)));

            assertThat(service.getVisibleMenuTreeForRole(null)).hasSize(1);
            verify(loadPermissionCodesPort, never()).findByRoleCode(any());
        }
    }
}
