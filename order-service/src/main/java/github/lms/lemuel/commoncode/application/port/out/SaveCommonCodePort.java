package github.lms.lemuel.commoncode.application.port.out;

import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;

public interface SaveCommonCodePort {

    CommonCodeGroup saveGroup(CommonCodeGroup group);

    void deleteGroupByCode(String groupCode);

    CommonCode saveCode(CommonCode code);

    void deleteCodeById(Long id);
}
