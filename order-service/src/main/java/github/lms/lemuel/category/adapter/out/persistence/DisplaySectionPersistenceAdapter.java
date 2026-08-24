package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.category.application.port.out.DisplaySectionPort;
import github.lms.lemuel.category.domain.DisplaySection;
import github.lms.lemuel.category.domain.DisplaySectionItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class DisplaySectionPersistenceAdapter implements DisplaySectionPort {

    private final SpringDataDisplaySectionRepository sectionRepository;
    private final SpringDataDisplaySectionItemRepository itemRepository;

    public DisplaySectionPersistenceAdapter(SpringDataDisplaySectionRepository sectionRepository,
                                            SpringDataDisplaySectionItemRepository itemRepository) {
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
    }

    @Override
    public Optional<DisplaySection> findByCode(String code) {
        return sectionRepository.findByCode(code).map(DisplaySectionPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<DisplaySection> findById(Long sectionId) {
        return sectionRepository.findById(sectionId).map(DisplaySectionPersistenceAdapter::toDomain);
    }

    @Override
    public List<DisplaySection> loadAll() {
        return sectionRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(DisplaySectionPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<DisplaySectionItem> loadItems(Long sectionId) {
        return itemRepository.findBySectionIdOrderByPinnedDescSortOrderAscProductIdAsc(sectionId)
                .stream()
                .map(DisplaySectionPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public DisplaySection save(DisplaySection section) {
        DisplaySectionJpaEntity entity;
        if (section.getId() == null) {
            entity = new DisplaySectionJpaEntity(null, section.getCode(), section.getName(),
                    section.getKind(), section.getCategoryId(), section.getStartsAt(),
                    section.getEndsAt(), section.getSortOrder(), section.isActive());
        } else {
            entity = sectionRepository.findById(section.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "DisplaySection 사라짐 (id=" + section.getId() + ")"));
            entity.applyDomainState(section.getName(), section.getCategoryId(), section.getStartsAt(),
                    section.getEndsAt(), section.getSortOrder(), section.isActive());
        }
        return toDomain(sectionRepository.save(entity));
    }

    @Override
    public DisplaySectionItem saveItem(DisplaySectionItem item) {
        DisplaySectionItemJpaEntity.Id id =
                new DisplaySectionItemJpaEntity.Id(item.getSectionId(), item.getProductId());
        DisplaySectionItemJpaEntity entity = itemRepository.findById(id)
                .orElseGet(() -> new DisplaySectionItemJpaEntity(item.getSectionId(),
                        item.getProductId(), item.getSortOrder(), item.isPinned()));
        entity.applyDomainState(item.getSortOrder(), item.isPinned());
        return toDomain(itemRepository.save(entity));
    }

    @Override
    public void removeItem(Long sectionId, Long productId) {
        itemRepository.deleteById(new DisplaySectionItemJpaEntity.Id(sectionId, productId));
    }

    private static DisplaySection toDomain(DisplaySectionJpaEntity e) {
        return DisplaySection.rehydrate(e.getId(), e.getCode(), e.getName(), e.getKind(),
                e.getCategoryId(), e.getStartsAt(), e.getEndsAt(), e.getSortOrder(), e.isActive());
    }

    private static DisplaySectionItem toDomain(DisplaySectionItemJpaEntity e) {
        return DisplaySectionItem.of(e.getSectionId(), e.getProductId(), e.getSortOrder(), e.isPinned());
    }
}
