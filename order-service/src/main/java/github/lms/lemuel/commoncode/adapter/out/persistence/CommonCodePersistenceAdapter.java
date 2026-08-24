package github.lms.lemuel.commoncode.adapter.out.persistence;

import github.lms.lemuel.commoncode.application.port.out.LoadCommonCodePort;
import github.lms.lemuel.commoncode.application.port.out.SaveCommonCodePort;
import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CommonCodePersistenceAdapter implements LoadCommonCodePort, SaveCommonCodePort {

    private final SpringDataCommonCodeGroupRepository groupRepository;
    private final SpringDataCommonCodeRepository codeRepository;

    // ---- LoadCommonCodePort ----

    @Override
    public List<CommonCodeGroup> findAllGroups() {
        return groupRepository.findAll().stream()
                .map(this::toDomainGroup)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<CommonCodeGroup> findGroupByCode(String groupCode) {
        return groupRepository.findById(groupCode).map(this::toDomainGroup);
    }

    @Override
    public List<CommonCode> findCodesByGroupCode(String groupCode) {
        return codeRepository.findByGroupCodeOrderBySortOrderAsc(groupCode).stream()
                .map(this::toDomainCode)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<CommonCode> findCodeById(Long id) {
        return codeRepository.findById(id).map(this::toDomainCode);
    }

    // ---- SaveCommonCodePort ----

    @Override
    public CommonCodeGroup saveGroup(CommonCodeGroup group) {
        CommonCodeGroupJpaEntity entity = toEntityGroup(group);
        CommonCodeGroupJpaEntity saved = groupRepository.save(entity);
        return toDomainGroup(saved);
    }

    @Override
    @Transactional
    public void deleteGroupByCode(String groupCode) {
        codeRepository.deleteByGroupCode(groupCode);
        groupRepository.deleteById(groupCode);
    }

    @Override
    public CommonCode saveCode(CommonCode code) {
        CommonCodeJpaEntity entity = toEntityCode(code);
        CommonCodeJpaEntity saved = codeRepository.save(entity);
        return toDomainCode(saved);
    }

    @Override
    public void deleteCodeById(Long id) {
        codeRepository.deleteById(id);
    }

    // ---- Mapping ----

    private CommonCodeGroup toDomainGroup(CommonCodeGroupJpaEntity entity) {
        return CommonCodeGroup.rehydrate(
                entity.getGroupCode(),
                entity.getName(),
                entity.getDescription(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private CommonCodeGroupJpaEntity toEntityGroup(CommonCodeGroup domain) {
        CommonCodeGroupJpaEntity entity = new CommonCodeGroupJpaEntity();
        entity.setGroupCode(domain.getGroupCode());
        entity.setName(domain.getName());
        entity.setDescription(domain.getDescription());
        entity.setActive(domain.isActive());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private CommonCode toDomainCode(CommonCodeJpaEntity entity) {
        return CommonCode.rehydrate(
                entity.getId(),
                entity.getGroupCode(),
                entity.getCode(),
                entity.getLabel(),
                entity.getSortOrder(),
                entity.isActive(),
                entity.getExtra1(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private CommonCodeJpaEntity toEntityCode(CommonCode domain) {
        CommonCodeJpaEntity entity = new CommonCodeJpaEntity();
        entity.setId(domain.getId());
        entity.setGroupCode(domain.getGroupCode());
        entity.setCode(domain.getCode());
        entity.setLabel(domain.getLabel());
        entity.setSortOrder(domain.getSortOrder());
        entity.setActive(domain.isActive());
        entity.setExtra1(domain.getExtra1());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
