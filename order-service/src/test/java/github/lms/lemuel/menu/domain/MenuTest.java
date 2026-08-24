package github.lms.lemuel.menu.domain;

import github.lms.lemuel.menu.domain.exception.MenuInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuTest {

    private static MenuAttributes attrs(String name, String path) {
        return MenuAttributes.item(name, path, MenuArea.BACKOFFICE, null);
    }

    @Test @DisplayName("create - 이름 앞뒤 공백 트림 및 기본값 설정")
    void create_trimsName() {
        Menu menu = Menu.create(
                new MenuAttributes("  대시보드  ", null, "/dash", "icon", "설명",
                        MenuArea.BACKOFFICE, MenuType.ITEM, "ADMIN", null),
                1L, 3, true);

        assertThat(menu.getName()).isEqualTo("대시보드");
        assertThat(menu.getPath()).isEqualTo("/dash");
        assertThat(menu.getParentId()).isEqualTo(1L);
        assertThat(menu.getSortOrder()).isEqualTo(3);
        assertThat(menu.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(menu.getArea()).isEqualTo(MenuArea.BACKOFFICE);
        assertThat(menu.getType()).isEqualTo(MenuType.ITEM);
        assertThat(menu.getDescription()).isEqualTo("설명");
        assertThat(menu.isVisible()).isTrue();
        assertThat(menu.isActive()).isTrue(); // 기본 생성자 default
        assertThat(menu.getChildren()).isEmpty();
        assertThat(menu.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create - 이름이 비어 있으면 예외")
    void create_blankName() {
        assertThatThrownBy(() -> Menu.create(attrs("  ", "/p"), null, 0, true))
                .isInstanceOf(MenuInvariantViolationException.class);
        assertThatThrownBy(() -> Menu.create(attrs(null, "/p"), null, 0, true))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    @Test @DisplayName("update - 필드 전체 갱신")
    void update() {
        Menu menu = Menu.create(attrs("old", "/old"), null, 0, true);
        menu.update(new MenuAttributes("new", "짧은", "/new", "ic", "부제",
                MenuArea.CEO, MenuType.GROUP, "USER", "ORDER_READ"), 2L, 5, false, false);

        assertThat(menu.getName()).isEqualTo("new");
        assertThat(menu.getShortName()).isEqualTo("짧은");
        assertThat(menu.getPath()).isEqualTo("/new");
        assertThat(menu.getIcon()).isEqualTo("ic");
        assertThat(menu.getParentId()).isEqualTo(2L);
        assertThat(menu.getSortOrder()).isEqualTo(5);
        assertThat(menu.getRequiredRole()).isEqualTo("USER");
        assertThat(menu.getRequiredPermission()).isEqualTo("ORDER_READ");
        assertThat(menu.getArea()).isEqualTo(MenuArea.CEO);
        assertThat(menu.getType()).isEqualTo(MenuType.GROUP);
        assertThat(menu.isVisible()).isFalse();
        assertThat(menu.isActive()).isFalse();
    }

    @Test @DisplayName("update - 이름이 비어 있으면 예외")
    void update_blankName() {
        Menu menu = Menu.create(attrs("old", "/old"), null, 0, true);
        assertThatThrownBy(() -> menu.update(attrs("", "/n"), null, 0, true, true))
                .isInstanceOf(MenuInvariantViolationException.class);
    }

    @Test @DisplayName("addChild - 자식 목록에 추가")
    void addChild() {
        Menu parent = Menu.create(attrs("p", "/p"), null, 0, true);
        Menu child = Menu.create(attrs("c", "/c"), null, 0, true);
        parent.addChild(child);

        assertThat(parent.getChildren()).containsExactly(child);
    }

    @Test @DisplayName("setters - 식별자/시간 세팅")
    void setters() {
        Menu menu = Menu.create(attrs("m", "/m"), null, 0, true);
        menu.assignId(9L);
        menu.replaceChildren(new java.util.ArrayList<>());
        assertThat(menu.getId()).isEqualTo(9L);
        assertThat(menu.getChildren()).isEmpty();
    }

    @Nested
    @DisplayName("불변식 — 영역·타입·경로")
    class Invariants {

        @Test @DisplayName("area 가 없으면 예외 — 어느 셸에도 붙지 못하는 메뉴를 막는다")
        void area_required() {
            assertThatThrownBy(() -> Menu.create(
                    new MenuAttributes("이름", null, "/p", null, null, null, MenuType.ITEM, null, null),
                    null, 0, true))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("영역");
        }

        @Test @DisplayName("ITEM/GROUP 은 경로가 필수")
        void linkTypes_requirePath() {
            assertThatThrownBy(() -> Menu.create(attrs("이름", null), null, 0, true))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("경로가 필수");

            assertThatThrownBy(() -> Menu.create(
                    new MenuAttributes("묶음", null, "  ", null, null,
                            MenuArea.CEO, MenuType.GROUP, null, null),
                    null, 0, true))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("경로가 필수");
        }

        @Test @DisplayName("DIVIDER 는 경로를 가질 수 없다")
        void divider_rejectsPath() {
            assertThatThrownBy(() -> Menu.create(
                    new MenuAttributes("구분", null, "/x", null, null,
                            MenuArea.CEO, MenuType.DIVIDER, null, null),
                    null, 0, true))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("구분선");
        }

        @Test @DisplayName("DIVIDER 는 경로 없이 생성된다")
        void divider_withoutPath() {
            Menu divider = Menu.create(
                    new MenuAttributes("구분", null, null, null, null,
                            MenuArea.CEO, MenuType.DIVIDER, null, null),
                    null, 0, true);
            assertThat(divider.getPath()).isNull();
            assertThat(divider.getType()).isEqualTo(MenuType.DIVIDER);
        }

        @Test @DisplayName("경로는 '/' 로 시작해야 한다 — 상대경로는 라우터가 못 찾는다")
        void path_mustBeAbsolute() {
            assertThatThrownBy(() -> Menu.create(attrs("이름", "admin/x"), null, 0, true))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("'/'");
        }

        @Test @DisplayName("역할 CSV 에 빈 항목이 있으면 예외")
        void roles_rejectBlankToken() {
            assertThatThrownBy(() -> Menu.create(
                    MenuAttributes.item("이름", "/p", MenuArea.SHOP, "ADMIN,,USER"), null, 0, true))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("빈 항목");
        }
    }

    @Nested
    @DisplayName("깊이 — MAX_DEPTH 3")
    class Depth {

        @Test @DisplayName("3 단계까지 허용")
        void within() {
            assertThatCode(() -> Menu.requireDepthWithin(3)).doesNotThrowAnyException();
        }

        @Test @DisplayName("4 단계는 거부 — 셸이 그리지 못하는 트리")
        void beyond() {
            assertThatThrownBy(() -> Menu.requireDepthWithin(4))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("최대 3");
        }
    }

    @Nested
    @DisplayName("영역 일치 — 자식은 부모 영역을 따른다")
    class SameArea {

        @Test @DisplayName("부모와 영역이 다르면 예외")
        void mismatch() {
            Menu parent = Menu.create(MenuAttributes.item("부모", "/p", MenuArea.CEO, null), null, 0, true);
            Menu child = Menu.create(MenuAttributes.item("자식", "/c", MenuArea.SYSTEM, null), null, 0, true);

            assertThatThrownBy(() -> child.requireSameAreaAs(parent))
                    .isInstanceOf(MenuInvariantViolationException.class)
                    .hasMessageContaining("같은 영역");
        }

        @Test @DisplayName("부모가 null(최상위)이면 검증하지 않는다")
        void rootIsExempt() {
            Menu root = Menu.create(MenuAttributes.item("루트", "/r", MenuArea.CEO, null), null, 0, true);
            assertThatCode(() -> root.requireSameAreaAs(null)).doesNotThrowAnyException();
        }

        @Test @DisplayName("영역이 같으면 통과")
        void match() {
            Menu parent = Menu.create(MenuAttributes.item("부모", "/p", MenuArea.CEO, null), null, 0, true);
            Menu child = Menu.create(MenuAttributes.item("자식", "/c", MenuArea.CEO, null), null, 0, true);
            assertThatCode(() -> child.requireSameAreaAs(parent)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("접근 판정 — isAccessibleBy")
    class Access {

        private Menu menu(String roles, String permission, boolean visible, boolean active) {
            Menu m = Menu.create(
                    new MenuAttributes("메뉴", null, "/p", null, null,
                            MenuArea.BACKOFFICE, MenuType.ITEM, roles, permission),
                    null, 0, visible);
            if (!active) {
                m.update(new MenuAttributes("메뉴", null, "/p", null, null,
                        MenuArea.BACKOFFICE, MenuType.ITEM, roles, permission), null, 0, visible, false);
            }
            return m;
        }

        @Test @DisplayName("역할 제한이 없으면 미인증에게도 보인다")
        void noRestriction_visibleToAnonymous() {
            assertThat(menu(null, null, true, true).isAccessibleBy(null, Set.of())).isTrue();
        }

        @Test @DisplayName("allowlist 에 포함된 역할만 통과")
        void roleAllowlist() {
            Menu m = menu("ADMIN,MANAGER", null, true, true);

            assertThat(m.isAccessibleBy("ADMIN", Set.of())).isTrue();
            assertThat(m.isAccessibleBy("MANAGER", Set.of())).isTrue();
            assertThat(m.isAccessibleBy("USER", Set.of())).isFalse();
            assertThat(m.isAccessibleBy(null, Set.of())).isFalse();
        }

        @Test @DisplayName("역할 비교는 대소문자를 가리지 않는다")
        void roleCaseInsensitive() {
            assertThat(menu("admin", null, true, true).isAccessibleBy("ADMIN", Set.of())).isTrue();
            assertThat(menu("ADMIN", null, true, true).isAccessibleBy("admin", Set.of())).isTrue();
        }

        @Test @DisplayName("requiredPermission 이 있으면 권한 보유자만 통과")
        void permissionGate() {
            Menu m = menu("ADMIN", "SYSTEM_MENU_MANAGE", true, true);

            assertThat(m.isAccessibleBy("ADMIN", Set.of("SYSTEM_MENU_MANAGE"))).isTrue();
            assertThat(m.isAccessibleBy("ADMIN", Set.of("ORDER_READ"))).isFalse();
            assertThat(m.isAccessibleBy("ADMIN", null)).isFalse();
        }

        @Test @DisplayName("visible=false 또는 active=false 면 누구에게도 보이지 않는다")
        void hiddenOrInactive() {
            assertThat(menu(null, null, false, true).isAccessibleBy("ADMIN", Set.of())).isFalse();
            assertThat(menu(null, null, true, false).isAccessibleBy("ADMIN", Set.of())).isFalse();
        }

        @Test @DisplayName("allowedRoles - CSV 를 공백 제거·대문자로 파싱")
        void allowedRolesParsing() {
            assertThat(menu(" admin , manager ", null, true, true).allowedRoles())
                    .containsExactly("ADMIN", "MANAGER");
            assertThat(menu(null, null, true, true).allowedRoles()).isEmpty();
        }
    }

    @Nested
    @DisplayName("표시 라벨")
    class Label {

        @Test @DisplayName("shortName 이 있으면 상단 네비는 그것을 쓴다")
        void prefersShortName() {
            Menu m = Menu.create(new MenuAttributes("시스템 관리", "시스템", "/admin/system", null, null,
                    MenuArea.SYSTEM, MenuType.GROUP, "ADMIN", null), null, 0, true);

            assertThat(m.displayLabel()).isEqualTo("시스템");
            assertThat(m.getName()).isEqualTo("시스템 관리");
        }

        @Test @DisplayName("shortName 이 없으면 이름을 그대로 쓴다")
        void fallsBackToName() {
            assertThat(Menu.create(attrs("정산", "/admin/settlement"), null, 0, true).displayLabel())
                    .isEqualTo("정산");
        }
    }
}
