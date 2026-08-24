package github.lms.lemuel.menu.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Menu — 복원 왕복 커버리지")
class MenuSettersTest {

    @Test
    @DisplayName("rehydrate 로 모든 필드가 왕복한다")
    void allFields() {
        LocalDateTime t = LocalDateTime.now();
        Menu menu = Menu.rehydrate(1L, 2L,
                new MenuAttributes("메뉴", "짧은", "/path", "icon", "부제",
                        MenuArea.SYSTEM, MenuType.GROUP, "ADMIN", "SYSTEM_MENU_MANAGE"),
                9, false, false, t, t);

        assertThat(menu.getId()).isEqualTo(1L);
        assertThat(menu.getParentId()).isEqualTo(2L);
        assertThat(menu.getName()).isEqualTo("메뉴");
        assertThat(menu.getShortName()).isEqualTo("짧은");
        assertThat(menu.getPath()).isEqualTo("/path");
        assertThat(menu.getIcon()).isEqualTo("icon");
        assertThat(menu.getDescription()).isEqualTo("부제");
        assertThat(menu.getArea()).isEqualTo(MenuArea.SYSTEM);
        assertThat(menu.getType()).isEqualTo(MenuType.GROUP);
        assertThat(menu.getSortOrder()).isEqualTo(9);
        assertThat(menu.getRequiredRole()).isEqualTo("ADMIN");
        assertThat(menu.getRequiredPermission()).isEqualTo("SYSTEM_MENU_MANAGE");
        assertThat(menu.isVisible()).isFalse();
        assertThat(menu.isActive()).isFalse();
        assertThat(menu.getCreatedAt()).isEqualTo(t);
        assertThat(menu.getUpdatedAt()).isEqualTo(t);
    }

    @Test
    @DisplayName("복원은 검증하지 않는다 — 낡은 행 하나가 트리 조회를 막지 않도록")
    void rehydrateSkipsValidation() {
        LocalDateTime t = LocalDateTime.now();
        Menu legacy = Menu.rehydrate(1L, null,
                new MenuAttributes("", null, null, null, null, null, null, null, null),
                0, true, true, t, t);

        assertThat(legacy.getName()).isEmpty();
        assertThat(legacy.getArea()).isNull();
        assertThat(legacy.getType()).isEqualTo(MenuType.ITEM); // 기본값
    }
}
