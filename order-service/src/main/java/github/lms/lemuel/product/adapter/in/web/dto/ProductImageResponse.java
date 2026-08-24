package github.lms.lemuel.product.adapter.in.web.dto;

import github.lms.lemuel.product.domain.ProductImage;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProductImageResponse {
    private Long id;
    private Long productId;
    private String originalFileName;
    private String url;
    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Boolean isPrimary;
    private Integer orderIndex;
    private LocalDateTime createdAt;

    public static ProductImageResponse from(ProductImage image) {
        return new ProductImageResponse(
                image.getId(),
                image.getProductId(),
                image.getOriginalFileName(),
                image.getUrl(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getIsPrimary(),
                image.getOrderIndex(),
                image.getCreatedAt()
        );
    }
}
