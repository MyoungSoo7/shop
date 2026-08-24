package github.lms.lemuel.category.application.port.out;

import github.lms.lemuel.category.domain.DisplaySection;
import github.lms.lemuel.category.domain.DisplaySectionItem;

import java.util.List;
import java.util.Optional;

/**
 * 진열 편성 조회·저장 포트.
 *
 * <p>편성과 그 항목은 함께 읽고 함께 쓰는 하나의 애그리거트라 포트를 나누지 않는다.
 */
public interface DisplaySectionPort {

    Optional<DisplaySection> findByCode(String code);

    Optional<DisplaySection> findById(Long sectionId);

    /** 정렬 순서 오름차순. 노출 여부 판정은 도메인이 하므로 여기서는 거르지 않는다. */
    List<DisplaySection> loadAll();

    /** 고정 우선 → 정렬 순서 → 상품 id. */
    List<DisplaySectionItem> loadItems(Long sectionId);

    DisplaySection save(DisplaySection section);

    DisplaySectionItem saveItem(DisplaySectionItem item);

    void removeItem(Long sectionId, Long productId);
}
