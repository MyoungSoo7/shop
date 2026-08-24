package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.time.LocalDateTime;

/**
 * ProductImage Domain Entity
 * 상품 이미지 (갤러리 지원, 대표 이미지 지정)
 */
public class ProductImage {

    private Long id;
    private Long productId;
    private String originalFileName;
    private String storedFileName;
    private String filePath;
    private String url;
    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private String checksum;
    private Boolean isPrimary;
    private Integer orderIndex;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public ProductImage() {
        this.isPrimary = false;
        this.orderIndex = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public ProductImage(Long id, Long productId, String originalFileName, String storedFileName,
                        String filePath, String url, String contentType, Long sizeBytes,
                        Integer width, Integer height, String checksum, Boolean isPrimary,
                        Integer orderIndex, LocalDateTime createdAt, LocalDateTime updatedAt,
                        LocalDateTime deletedAt) {
        this.id = id;
        this.productId = productId;
        this.originalFileName = originalFileName;
        this.storedFileName = storedFileName;
        this.filePath = filePath;
        this.url = url;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.checksum = checksum;
        this.isPrimary = isPrimary != null ? isPrimary : false;
        this.orderIndex = orderIndex != null ? orderIndex : 0;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        this.deletedAt = deletedAt;
    }

    // 정적 팩토리 메서드
    public static ProductImage create(Long productId, String originalFileName, String storedFileName,
                                       String filePath, String url, String contentType, Long sizeBytes,
                                       Integer width, Integer height, Integer orderIndex) {
        ProductImage image = new ProductImage();
        image.productId = productId;
        image.originalFileName = originalFileName;
        image.storedFileName = storedFileName;
        image.filePath = filePath;
        image.url = url;
        image.contentType = contentType;
        image.sizeBytes = sizeBytes;
        image.width = width;
        image.height = height;
        image.orderIndex = orderIndex;
        image.validateContentType();
        image.validateFileSize();
        return image;
    }

    // 도메인 규칙: 허용된 이미지 타입만
    public void validateContentType() {
        if (contentType == null) {
            throw new ProductInvariantViolationException("Content type cannot be null");
        }
        if (!contentType.equals("image/jpeg") &&
            !contentType.equals("image/jpg") &&
            !contentType.equals("image/png") &&
            !contentType.equals("image/webp")) {
            throw new ProductInvariantViolationException("Invalid image type. Only jpg, jpeg, png, webp are allowed");
        }
    }

    // 도메인 규칙: 파일 크기 검증 (5MB)
    public void validateFileSize() {
        long maxSize = 5L * 1024 * 1024; // 5MB — long 산술로 계산(int 곱 후 넓히기는 상한을 키울 때 조용히 넘친다)
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new ProductInvariantViolationException("File size must be greater than 0");
        }
        if (sizeBytes > maxSize) {
            throw new ProductInvariantViolationException("File size exceeds maximum limit of 5MB");
        }
    }

    // 비즈니스 메서드: 대표 이미지로 지정
    public void markAsPrimary() {
        if (isDeleted()) {
            throw new InvalidProductStateException("Cannot mark deleted image as primary");
        }
        this.isPrimary = true;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 대표 이미지 해제
    public void unmarkAsPrimary() {
        this.isPrimary = false;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: 순서 변경
    public void changeOrder(Integer newOrderIndex) {
        if (newOrderIndex == null || newOrderIndex < 0) {
            throw new ProductInvariantViolationException("Order index must be zero or greater");
        }
        this.orderIndex = newOrderIndex;
        this.updatedAt = LocalDateTime.now();
    }

    // 비즈니스 메서드: soft delete
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.isPrimary = false; // 삭제 시 대표 이미지 해제
        this.updatedAt = LocalDateTime.now();
    }

    // 상태 확인 메서드
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /** Persistence 어댑터가 DB 부여 PK 를 주입할 때 사용(setter 대체). */
    public void assignId(Long id) {
        this.id = id;
    }

    /** 파일 체크섬 확정(업로드 후 계산값 부착). */
    public void assignChecksum(String checksum) {
        this.checksum = checksum;
    }

    // Getters
    public Long getId() {
        return id;
    }


    public Long getProductId() {
        return productId;
    }


    public String getOriginalFileName() {
        return originalFileName;
    }


    public String getStoredFileName() {
        return storedFileName;
    }


    public String getFilePath() {
        return filePath;
    }


    public String getUrl() {
        return url;
    }


    public String getContentType() {
        return contentType;
    }


    public Long getSizeBytes() {
        return sizeBytes;
    }


    public Integer getWidth() {
        return width;
    }


    public Integer getHeight() {
        return height;
    }


    public String getChecksum() {
        return checksum;
    }


    public Boolean getIsPrimary() {
        return isPrimary;
    }


    public Integer getOrderIndex() {
        return orderIndex;
    }


    public LocalDateTime getCreatedAt() {
        return createdAt;
    }


    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }


    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

}
