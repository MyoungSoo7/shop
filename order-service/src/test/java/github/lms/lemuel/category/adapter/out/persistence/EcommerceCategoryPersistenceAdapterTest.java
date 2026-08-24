package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.category.domain.EcommerceCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 이커머스 카테고리 매퍼 + 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속).
 * 매퍼의 도메인↔엔티티 양방향 매핑과 어댑터의 리포지토리 위임을 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class EcommerceCategoryPersistenceAdapterTest {

    @Mock SpringDataEcommerceCategoryRepository repository;
    private final EcommerceCategoryMapper mapper = new EcommerceCategoryMapper();

    private EcommerceCategoryPersistenceAdapter adapter() {
        return new EcommerceCategoryPersistenceAdapter(repository, mapper);
    }

    private EcommerceCategoryJpaEntity entity(long id, Long parentId) {
        LocalDateTime now = LocalDateTime.now();
        return new EcommerceCategoryJpaEntity(id, "전자제품", "electronics", parentId,
                parentId == null ? 0 : 1, 5, true, now, now, null);
    }

    @Test
    @DisplayName("mapper: null 입력은 null 반환")
    void mapper_nullSafe() {
        assertThat(mapper.toJpaEntity(null)).isNull();
        assertThat(mapper.toDomainEntity(null)).isNull();
    }

    @Test
    @DisplayName("mapper: 도메인→엔티티→도메인 왕복이 필드를 보존한다")
    void mapper_roundTrip() {
        EcommerceCategory domain = new EcommerceCategory(3L, "가전", "home-appliance",
                1L, 1, 2, true, LocalDateTime.now(), LocalDateTime.now(), null);

        EcommerceCategory back = mapper.toDomainEntity(mapper.toJpaEntity(domain));

        assertThat(back.getId()).isEqualTo(3L);
        assertThat(back.getName()).isEqualTo("가전");
        assertThat(back.getSlug()).isEqualTo("home-appliance");
        assertThat(back.getParentId()).isEqualTo(1L);
        assertThat(back.getDepth()).isEqualTo(1);
        assertThat(back.getSortOrder()).isEqualTo(2);
        assertThat(back.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("findByIdNotDeleted: 존재 시 도메인 매핑")
    void findByIdNotDeleted() {
        when(repository.findByIdNotDeleted(1L)).thenReturn(Optional.of(entity(1L, null)));
        Optional<EcommerceCategory> c = adapter().findByIdNotDeleted(1L);
        assertThat(c).isPresent();
        assertThat(c.get().getSlug()).isEqualTo("electronics");
    }

    @Test
    @DisplayName("findBySlug: 존재 시 도메인 매핑")
    void findBySlug() {
        when(repository.findBySlug("electronics")).thenReturn(Optional.of(entity(1L, null)));
        assertThat(adapter().findBySlug("electronics")).isPresent();
    }

    @Test
    @DisplayName("findAllNotDeleted: 목록 매핑")
    void findAllNotDeleted() {
        when(repository.findAllNotDeleted()).thenReturn(List.of(entity(1L, null), entity(2L, 1L)));
        assertThat(adapter().findAllNotDeleted()).hasSize(2);
    }

    @Test
    @DisplayName("findAllActiveNotDeleted: 목록 매핑")
    void findAllActiveNotDeleted() {
        when(repository.findAllActiveNotDeleted()).thenReturn(List.of(entity(1L, null)));
        assertThat(adapter().findAllActiveNotDeleted()).hasSize(1);
    }

    @Test
    @DisplayName("findByParentId: 목록 매핑")
    void findByParentId() {
        when(repository.findByParentId(1L)).thenReturn(List.of(entity(2L, 1L)));
        assertThat(adapter().findByParentId(1L)).hasSize(1);
    }

    @Test
    @DisplayName("countChildrenByParentId / hasProducts: 리포지토리 위임")
    void delegations() {
        when(repository.countChildrenByParentId(1L)).thenReturn(3L);
        when(repository.hasProducts(1L)).thenReturn(true);
        assertThat(adapter().countChildrenByParentId(1L)).isEqualTo(3L);
        assertThat(adapter().hasProducts(1L)).isTrue();
    }

    @Test
    @DisplayName("save: 저장 후 저장본을 도메인으로 반환")
    void save() {
        when(repository.save(any(EcommerceCategoryJpaEntity.class)))
                .thenReturn(entity(9L, null));
        EcommerceCategory saved = adapter().save(EcommerceCategory.createRoot("전자제품", "electronics", 5));
        assertThat(saved.getId()).isEqualTo(9L);
    }
}
