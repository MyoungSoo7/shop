package github.lms.lemuel.commoncode.application.port.in;

import github.lms.lemuel.commoncode.domain.CommonCodeGroup;

import java.util.List;

public interface CommonCodeGroupUseCase {

    List<CommonCodeGroup> getAllGroups();

    CommonCodeGroup createGroup(CreateGroupCommand command);

    CommonCodeGroup updateGroup(String groupCode, UpdateGroupCommand command);

    void deleteGroup(String groupCode);

    record CreateGroupCommand(
            String groupCode,
            String name,
            String description
    ) {}

    record UpdateGroupCommand(
            String name,
            String description,
            boolean active
    ) {}
}
