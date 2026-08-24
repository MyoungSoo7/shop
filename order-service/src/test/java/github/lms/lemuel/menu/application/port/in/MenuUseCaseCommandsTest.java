package github.lms.lemuel.menu.application.port.in;

import github.lms.lemuel.menu.application.port.in.MenuUseCase.CreateMenuCommand;
import github.lms.lemuel.menu.application.port.in.MenuUseCase.UpdateMenuCommand;
import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuAttributes;
import github.lms.lemuel.menu.domain.MenuType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MenuUseCaseCommandsTest {

    @Test @DisplayName("CreateMenuCommand - 접근자 값 보존")
    void createCommand() {
        MenuAttributes attrs = new MenuAttributes("이름", "짧은", "/path", "icon", "부제",
                MenuArea.SYSTEM, MenuType.GROUP, "ADMIN", "SYSTEM_MENU_MANAGE");
        CreateMenuCommand cmd = new CreateMenuCommand(attrs, 1L, 3, true);

        assertThat(cmd.attributes().name()).isEqualTo("이름");
        assertThat(cmd.attributes().shortName()).isEqualTo("짧은");
        assertThat(cmd.attributes().path()).isEqualTo("/path");
        assertThat(cmd.attributes().icon()).isEqualTo("icon");
        assertThat(cmd.attributes().description()).isEqualTo("부제");
        assertThat(cmd.attributes().area()).isEqualTo(MenuArea.SYSTEM);
        assertThat(cmd.attributes().type()).isEqualTo(MenuType.GROUP);
        assertThat(cmd.attributes().requiredRole()).isEqualTo("ADMIN");
        assertThat(cmd.attributes().requiredPermission()).isEqualTo("SYSTEM_MENU_MANAGE");
        assertThat(cmd.parentId()).isEqualTo(1L);
        assertThat(cmd.sortOrder()).isEqualTo(3);
        assertThat(cmd.visible()).isTrue();
    }

    @Test @DisplayName("UpdateMenuCommand - 접근자 값 보존")
    void updateCommand() {
        UpdateMenuCommand cmd = new UpdateMenuCommand(
                MenuAttributes.item("이름", "/path", MenuArea.SHOP, "USER"), 2L, 5, false, true);

        assertThat(cmd.attributes().name()).isEqualTo("이름");
        assertThat(cmd.attributes().path()).isEqualTo("/path");
        assertThat(cmd.attributes().area()).isEqualTo(MenuArea.SHOP);
        assertThat(cmd.attributes().type()).isEqualTo(MenuType.ITEM);
        assertThat(cmd.attributes().requiredRole()).isEqualTo("USER");
        assertThat(cmd.parentId()).isEqualTo(2L);
        assertThat(cmd.sortOrder()).isEqualTo(5);
        assertThat(cmd.visible()).isFalse();
        assertThat(cmd.active()).isTrue();
    }

    @Test @DisplayName("ReorderItemCommand - 접근자 값 보존")
    void reorderCommand() {
        MenuUseCase.ReorderItemCommand cmd = new MenuUseCase.ReorderItemCommand(7L, 3L, 2);

        assertThat(cmd.id()).isEqualTo(7L);
        assertThat(cmd.parentId()).isEqualTo(3L);
        assertThat(cmd.sortOrder()).isEqualTo(2);
    }
}
