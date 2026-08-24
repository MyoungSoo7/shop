package github.lms.lemuel.commoncode.application.service;
import github.lms.lemuel.commoncode.domain.exception.CommonCodeInvariantViolationException;

import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase.CreateGroupCommand;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase.UpdateGroupCommand;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeUseCase.CreateCodeCommand;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeUseCase.UpdateCodeCommand;
import github.lms.lemuel.commoncode.application.port.out.LoadCommonCodePort;
import github.lms.lemuel.commoncode.application.port.out.SaveCommonCodePort;
import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommonCodeService — 공통코드 그룹/코드 CRUD")
class CommonCodeServiceTest {

    @Mock LoadCommonCodePort loadPort;
    @Mock SaveCommonCodePort savePort;
    @InjectMocks CommonCodeService service;

    // ---- Group ----

    @Test
    @DisplayName("getAllGroups — 포트 위임")
    void getAllGroups() {
        when(loadPort.findAllGroups()).thenReturn(List.of(CommonCodeGroup.create("G1", "그룹1", null)));
        assertThat(service.getAllGroups()).hasSize(1);
        verify(loadPort).findAllGroups();
    }

    @Test
    @DisplayName("createGroup — 도메인 생성 후 저장")
    void createGroup() {
        when(savePort.saveGroup(any())).thenAnswer(i -> i.getArgument(0));
        CommonCodeGroup saved = service.createGroup(new CreateGroupCommand("pay", "결제수단", "설명"));
        assertThat(saved.getGroupCode()).isEqualTo("PAY");
        verify(savePort).saveGroup(any());
    }

    @Test
    @DisplayName("updateGroup — 존재하면 갱신")
    void updateGroup_ok() {
        CommonCodeGroup g = CommonCodeGroup.create("PAY", "결제수단", "설명");
        when(loadPort.findGroupByCode("PAY")).thenReturn(Optional.of(g));
        when(savePort.saveGroup(any())).thenAnswer(i -> i.getArgument(0));
        CommonCodeGroup saved = service.updateGroup("PAY", new UpdateGroupCommand("결제방식", "새설명", false));
        assertThat(saved.getName()).isEqualTo("결제방식");
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    @DisplayName("updateGroup — 없으면 예외")
    void updateGroup_missing() {
        when(loadPort.findGroupByCode("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateGroup("X", new UpdateGroupCommand("n", "d", true)))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    @Test
    @DisplayName("deleteGroup — 존재하면 삭제")
    void deleteGroup_ok() {
        when(loadPort.findGroupByCode("PAY")).thenReturn(Optional.of(CommonCodeGroup.create("PAY", "n", null)));
        service.deleteGroup("PAY");
        verify(savePort).deleteGroupByCode("PAY");
    }

    @Test
    @DisplayName("deleteGroup — 없으면 예외")
    void deleteGroup_missing() {
        when(loadPort.findGroupByCode("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteGroup("X")).isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    // ---- Code ----

    @Test
    @DisplayName("getCodesByGroup — 그룹 존재 검증 후 코드 목록")
    void getCodesByGroup_ok() {
        when(loadPort.findGroupByCode("PAY")).thenReturn(Optional.of(CommonCodeGroup.create("PAY", "n", null)));
        when(loadPort.findCodesByGroupCode("PAY")).thenReturn(List.of(CommonCode.create("PAY", "CARD", "카드", 0, null)));
        assertThat(service.getCodesByGroup("PAY")).hasSize(1);
    }

    @Test
    @DisplayName("getCodesByGroup — 그룹 없으면 예외")
    void getCodesByGroup_missing() {
        when(loadPort.findGroupByCode("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getCodesByGroup("X")).isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    @Test
    @DisplayName("createCode — 그룹 존재 시 생성")
    void createCode_ok() {
        when(loadPort.findGroupByCode("PAY")).thenReturn(Optional.of(CommonCodeGroup.create("PAY", "n", null)));
        when(savePort.saveCode(any())).thenAnswer(i -> i.getArgument(0));
        CommonCode saved = service.createCode(new CreateCodeCommand("PAY", "card", "카드", 1, "x"));
        assertThat(saved.getCode()).isEqualTo("CARD");
        assertThat(saved.getSortOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("createCode — 그룹 없으면 예외")
    void createCode_missingGroup() {
        when(loadPort.findGroupByCode("X")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createCode(new CreateCodeCommand("X", "c", "l", 0, null)))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    @Test
    @DisplayName("updateCode — 존재하면 갱신")
    void updateCode_ok() {
        CommonCode c = CommonCode.create("PAY", "CARD", "카드", 0, null);
        when(loadPort.findCodeById(7L)).thenReturn(Optional.of(c));
        when(savePort.saveCode(any())).thenAnswer(i -> i.getArgument(0));
        CommonCode saved = service.updateCode(7L, new UpdateCodeCommand("신용카드", 3, false, "e"));
        assertThat(saved.getLabel()).isEqualTo("신용카드");
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    @DisplayName("updateCode — 없으면 예외")
    void updateCode_missing() {
        when(loadPort.findCodeById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateCode(99L, new UpdateCodeCommand("l", 0, true, null)))
                .isInstanceOf(CommonCodeInvariantViolationException.class);
    }

    @Test
    @DisplayName("deleteCode — 존재하면 삭제")
    void deleteCode_ok() {
        when(loadPort.findCodeById(5L)).thenReturn(Optional.of(CommonCode.create("PAY", "CARD", "카드", 0, null)));
        service.deleteCode(5L);
        verify(savePort).deleteCodeById(5L);
    }

    @Test
    @DisplayName("deleteCode — 없으면 예외")
    void deleteCode_missing() {
        when(loadPort.findCodeById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteCode(1L)).isInstanceOf(CommonCodeInvariantViolationException.class);
    }
}
