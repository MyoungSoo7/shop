package github.lms.lemuel.commoncode.application.port.in;

import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase.CreateGroupCommand;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase.UpdateGroupCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonCodeGroupUseCaseCommandsTest {

    @Test @DisplayName("CreateGroupCommand - 접근자 값 보존")
    void createCommand() {
        CreateGroupCommand cmd = new CreateGroupCommand("ORDER_STATUS", "주문상태", "설명");

        assertThat(cmd.groupCode()).isEqualTo("ORDER_STATUS");
        assertThat(cmd.name()).isEqualTo("주문상태");
        assertThat(cmd.description()).isEqualTo("설명");
    }

    @Test @DisplayName("UpdateGroupCommand - 접근자 값 보존")
    void updateCommand() {
        UpdateGroupCommand cmd = new UpdateGroupCommand("이름", "설명", false);

        assertThat(cmd.name()).isEqualTo("이름");
        assertThat(cmd.description()).isEqualTo("설명");
        assertThat(cmd.active()).isFalse();
    }
}
