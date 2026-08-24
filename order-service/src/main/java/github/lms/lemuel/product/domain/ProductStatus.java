package github.lms.lemuel.product.domain;

/**
 * 상품 상태 Enum
 */
public enum ProductStatus {
    ACTIVE,      // 판매 중
    INACTIVE,    // 판매 중지
    OUT_OF_STOCK, // 품절
    DISCONTINUED; // 단종

    public static ProductStatus fromString(String status) {
        try {
            return ProductStatus.valueOf(status.toUpperCase());
        } catch (Exception e) {
            return ACTIVE; // 기본값
        }
    }
}
