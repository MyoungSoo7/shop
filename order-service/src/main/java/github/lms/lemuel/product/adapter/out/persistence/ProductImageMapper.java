package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.ProductImage;
import org.springframework.stereotype.Component;

@Component
public class ProductImageMapper {

    public ProductImageJpaEntity toJpaEntity(ProductImage image) {
        if (image == null) {
            return null;
        }

        return new ProductImageJpaEntity(
                image.getId(),
                image.getProductId(),
                image.getOriginalFileName(),
                image.getStoredFileName(),
                image.getFilePath(),
                image.getUrl(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getChecksum(),
                image.getIsPrimary(),
                image.getOrderIndex(),
                image.getCreatedAt(),
                image.getUpdatedAt(),
                image.getDeletedAt()
        );
    }

    public ProductImage toDomainEntity(ProductImageJpaEntity jpaEntity) {
        if (jpaEntity == null) {
            return null;
        }

        return new ProductImage(
                jpaEntity.getId(),
                jpaEntity.getProductId(),
                jpaEntity.getOriginalFileName(),
                jpaEntity.getStoredFileName(),
                jpaEntity.getFilePath(),
                jpaEntity.getUrl(),
                jpaEntity.getContentType(),
                jpaEntity.getSizeBytes(),
                jpaEntity.getWidth(),
                jpaEntity.getHeight(),
                jpaEntity.getChecksum(),
                jpaEntity.getIsPrimary(),
                jpaEntity.getOrderIndex(),
                jpaEntity.getCreatedAt(),
                jpaEntity.getUpdatedAt(),
                jpaEntity.getDeletedAt()
        );
    }
}
