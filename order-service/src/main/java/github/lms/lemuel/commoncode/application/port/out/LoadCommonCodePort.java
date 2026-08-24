package github.lms.lemuel.commoncode.application.port.out;

import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;

import java.util.List;
import java.util.Optional;

public interface LoadCommonCodePort {

    List<CommonCodeGroup> findAllGroups();

    Optional<CommonCodeGroup> findGroupByCode(String groupCode);

    List<CommonCode> findCodesByGroupCode(String groupCode);

    Optional<CommonCode> findCodeById(Long id);
}
