package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.category.domain.EcommerceCategory;
import org.springframework.stereotype.Component;

@Component
public class EcommerceCategoryMapper {

    public EcommerceCategoryJpaEntity toJpaEntity(EcommerceCategory category) {
        if (category == null) {
            return null;
        }

        return new EcommerceCategoryJpaEntity(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                category.getDepth(),
                category.getSortOrder(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                category.getDeletedAt()
        );
    }

    public EcommerceCategory toDomainEntity(EcommerceCategoryJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }

        return new EcommerceCategory(
                jpaEntity.getId(),
                jpaEntity.getName(),
                jpaEntity.getSlug(),
                jpaEntity.getParentId(),
                jpaEntity.getDepth(),
                jpaEntity.getSortOrder(),
                jpaEntity.getIsActive(),
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt(),
                jpaEntity.getDeletedAt()
        );
    }
}
