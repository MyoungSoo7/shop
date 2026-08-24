package github.lms.lemuel.category.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataDisplaySectionItemRepository
        extends JpaRepository<DisplaySectionItemJpaEntity, DisplaySectionItemJpaEntity.Id> {

    /** 고정 우선 → 정렬 순서 → 상품 id. 도메인의 DISPLAY_ORDER 와 같은 규칙이다. */
    List<DisplaySectionItemJpaEntity> findBySectionIdOrderByPinnedDescSortOrderAscProductIdAsc(
            Long sectionId);
}
