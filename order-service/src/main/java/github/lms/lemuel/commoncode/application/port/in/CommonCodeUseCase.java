package github.lms.lemuel.commoncode.application.port.in;

import github.lms.lemuel.commoncode.domain.CommonCode;

import java.util.List;

public interface CommonCodeUseCase {

    List<CommonCode> getCodesByGroup(String groupCode);

    CommonCode createCode(CreateCodeCommand command);

    CommonCode updateCode(Long id, UpdateCodeCommand command);

    void deleteCode(Long id);

    record CreateCodeCommand(
            String groupCode,
            String code,
            String label,
            int sortOrder,
            String extra1
    ) {}

    record UpdateCodeCommand(
            String label,
            int sortOrder,
            boolean active,
            String extra1
    ) {}
}
