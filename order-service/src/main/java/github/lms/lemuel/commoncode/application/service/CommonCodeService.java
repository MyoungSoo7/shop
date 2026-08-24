package github.lms.lemuel.commoncode.application.service;

import github.lms.lemuel.commoncode.application.port.in.CommonCodeGroupUseCase;
import github.lms.lemuel.commoncode.application.port.in.CommonCodeUseCase;
import github.lms.lemuel.commoncode.application.port.out.LoadCommonCodePort;
import github.lms.lemuel.commoncode.application.port.out.SaveCommonCodePort;
import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.exception.CommonCodeInvariantViolationException;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CommonCodeService implements CommonCodeGroupUseCase, CommonCodeUseCase {

    private final LoadCommonCodePort loadCommonCodePort;
    private final SaveCommonCodePort saveCommonCodePort;

    // ---- Group ----

    @Override
    @Transactional(readOnly = true)
    public List<CommonCodeGroup> getAllGroups() {
        return loadCommonCodePort.findAllGroups();
    }

    @Override
    public CommonCodeGroup createGroup(CreateGroupCommand command) {
        CommonCodeGroup group = CommonCodeGroup.create(
                command.groupCode(),
                command.name(),
                command.description()
        );
        CommonCodeGroup saved = saveCommonCodePort.saveGroup(group);
        log.info("공통코드 그룹 생성: groupCode={}", saved.getGroupCode());
        return saved;
    }

    @Override
    public CommonCodeGroup updateGroup(String groupCode, UpdateGroupCommand command) {
        CommonCodeGroup group = loadCommonCodePort.findGroupByCode(groupCode)
                .orElseThrow(() -> new CommonCodeInvariantViolationException("존재하지 않는 그룹코드입니다: " + groupCode));
        group.update(command.name(), command.description(), command.active());
        CommonCodeGroup saved = saveCommonCodePort.saveGroup(group);
        log.info("공통코드 그룹 수정: groupCode={}", groupCode);
        return saved;
    }

    @Override
    public void deleteGroup(String groupCode) {
        loadCommonCodePort.findGroupByCode(groupCode)
                .orElseThrow(() -> new CommonCodeInvariantViolationException("존재하지 않는 그룹코드입니다: " + groupCode));
        saveCommonCodePort.deleteGroupByCode(groupCode);
        log.info("공통코드 그룹 삭제: groupCode={}", groupCode);
    }

    // ---- Code ----

    @Override
    @Transactional(readOnly = true)
    public List<CommonCode> getCodesByGroup(String groupCode) {
        loadCommonCodePort.findGroupByCode(groupCode)
                .orElseThrow(() -> new CommonCodeInvariantViolationException("존재하지 않는 그룹코드입니다: " + groupCode));
        return loadCommonCodePort.findCodesByGroupCode(groupCode);
    }

    @Override
    public CommonCode createCode(CreateCodeCommand command) {
        loadCommonCodePort.findGroupByCode(command.groupCode())
                .orElseThrow(() -> new CommonCodeInvariantViolationException("존재하지 않는 그룹코드입니다: " + command.groupCode()));
        CommonCode code = CommonCode.create(
                command.groupCode(),
                command.code(),
                command.label(),
                command.sortOrder(),
                command.extra1()
        );
        CommonCode saved = saveCommonCodePort.saveCode(code);
        log.info("공통코드 생성: groupCode={}, code={}", saved.getGroupCode(), saved.getCode());
        return saved;
    }

    @Override
    public CommonCode updateCode(Long id, UpdateCodeCommand command) {
        CommonCode code = loadCommonCodePort.findCodeById(id)
                .orElseThrow(() -> new CommonCodeInvariantViolationException("존재하지 않는 코드 ID입니다: " + id));
        code.update(command.label(), command.sortOrder(), command.active(), command.extra1());
        CommonCode saved = saveCommonCodePort.saveCode(code);
        log.info("공통코드 수정: id={}", id);
        return saved;
    }

    @Override
    public void deleteCode(Long id) {
        loadCommonCodePort.findCodeById(id)
                .orElseThrow(() -> new CommonCodeInvariantViolationException("존재하지 않는 코드 ID입니다: " + id));
        saveCommonCodePort.deleteCodeById(id);
        log.info("공통코드 삭제: id={}", id);
    }
}
