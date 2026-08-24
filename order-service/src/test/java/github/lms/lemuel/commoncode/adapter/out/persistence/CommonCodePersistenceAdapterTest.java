package github.lms.lemuel.commoncode.adapter.out.persistence;

import github.lms.lemuel.commoncode.domain.CommonCode;
import github.lms.lemuel.commoncode.domain.CommonCodeGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공통코드 영속 어댑터 매핑/위임 회귀 테스트.
 *
 * <p>도메인 ↔ JPA 엔티티 양방향 매핑과 리포지토리 위임을 Mockito 로 검증한다(실 DB 미접속).
 */
@ExtendWith(MockitoExtension.class)
class CommonCodePersistenceAdapterTest {

    @Mock SpringDataCommonCodeGroupRepository groupRepository;
    @Mock SpringDataCommonCodeRepository codeRepository;
    @InjectMocks CommonCodePersistenceAdapter adapter;

    private CommonCodeGroupJpaEntity groupEntity() {
        CommonCodeGroupJpaEntity e = new CommonCodeGroupJpaEntity();
        e.setGroupCode("ORDER_STATUS");
        e.setName("주문상태");
        e.setDescription("주문 상태코드");
        e.setActive(true);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    private CommonCodeJpaEntity codeEntity() {
        CommonCodeJpaEntity e = new CommonCodeJpaEntity();
        e.setId(10L);
        e.setGroupCode("ORDER_STATUS");
        e.setCode("PAID");
        e.setLabel("결제완료");
        e.setSortOrder(3);
        e.setActive(true);
        e.setExtra1("green");
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("findAllGroups: 엔티티가 도메인 그룹으로 매핑된다")
    void findAllGroups() {
        when(groupRepository.findAll()).thenReturn(List.of(groupEntity()));

        List<CommonCodeGroup> groups = adapter.findAllGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).getGroupCode()).isEqualTo("ORDER_STATUS");
        assertThat(groups.get(0).getName()).isEqualTo("주문상태");
        assertThat(groups.get(0).isActive()).isTrue();
    }

    @Test
    @DisplayName("findGroupByCode: id 조회 후 도메인 매핑")
    void findGroupByCode() {
        when(groupRepository.findById("ORDER_STATUS")).thenReturn(Optional.of(groupEntity()));

        Optional<CommonCodeGroup> group = adapter.findGroupByCode("ORDER_STATUS");

        assertThat(group).isPresent();
        assertThat(group.get().getDescription()).isEqualTo("주문 상태코드");
    }

    @Test
    @DisplayName("findGroupByCode: 없으면 empty")
    void findGroupByCode_empty() {
        when(groupRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThat(adapter.findGroupByCode("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("findCodesByGroupCode: 정렬 조회 결과 매핑")
    void findCodesByGroupCode() {
        when(codeRepository.findByGroupCodeOrderBySortOrderAsc("ORDER_STATUS"))
                .thenReturn(List.of(codeEntity()));

        List<CommonCode> codes = adapter.findCodesByGroupCode("ORDER_STATUS");

        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).getCode()).isEqualTo("PAID");
        assertThat(codes.get(0).getLabel()).isEqualTo("결제완료");
        assertThat(codes.get(0).getSortOrder()).isEqualTo(3);
        assertThat(codes.get(0).getExtra1()).isEqualTo("green");
    }

    @Test
    @DisplayName("findCodeById: id 조회 후 도메인 매핑")
    void findCodeById() {
        when(codeRepository.findById(10L)).thenReturn(Optional.of(codeEntity()));

        Optional<CommonCode> code = adapter.findCodeById(10L);

        assertThat(code).isPresent();
        assertThat(code.get().getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("saveGroup: 엔티티 저장 후 저장본을 도메인으로 반환")
    void saveGroup() {
        when(groupRepository.save(any(CommonCodeGroupJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CommonCodeGroup group = CommonCodeGroup.create("PAY_METHOD", "결제수단", "결제수단 코드");
        CommonCodeGroup saved = adapter.saveGroup(group);

        assertThat(saved.getGroupCode()).isEqualTo("PAY_METHOD");
        assertThat(saved.getName()).isEqualTo("결제수단");
        verify(groupRepository).save(any(CommonCodeGroupJpaEntity.class));
    }

    @Test
    @DisplayName("saveCode: 엔티티 저장 후 저장본을 도메인으로 반환")
    void saveCode() {
        when(codeRepository.save(any(CommonCodeJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CommonCode code = CommonCode.create("PAY_METHOD", "CARD", "카드", 1, null);
        CommonCode saved = adapter.saveCode(code);

        assertThat(saved.getCode()).isEqualTo("CARD");
        assertThat(saved.getLabel()).isEqualTo("카드");
        verify(codeRepository).save(any(CommonCodeJpaEntity.class));
    }

    @Test
    @DisplayName("deleteGroupByCode: 하위 코드 삭제 후 그룹 삭제")
    void deleteGroupByCode() {
        adapter.deleteGroupByCode("ORDER_STATUS");

        verify(codeRepository).deleteByGroupCode("ORDER_STATUS");
        verify(groupRepository).deleteById("ORDER_STATUS");
    }

    @Test
    @DisplayName("deleteCodeById: 리포지토리 위임")
    void deleteCodeById() {
        adapter.deleteCodeById(10L);
        verify(codeRepository).deleteById(10L);
    }
}
