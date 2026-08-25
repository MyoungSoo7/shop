package github.lms.lemuel.product.domain;

import github.lms.lemuel.common.exception.UnknownEnumValueException;

import java.util.Locale;

/**
 * 상품 상태 Enum
 */
public enum ProductStatus {
    ACTIVE,      // 판매 중
    INACTIVE,    // 판매 중지
    OUT_OF_STOCK, // 품절
    DISCONTINUED; // 단종

    /**
     * 문자열을 상품 상태로 옮긴다. 모르는 값이면 던진다.
     *
     * <p>예전 기본값은 {@link #ACTIVE} 였다. 하필 <b>판매 중</b>이라, 단종·판매중지 상품이
     * 읽기에 실패하는 순간 판매 가능한 상품이 된다. 실패를 가장 위험한 쪽으로 떨어뜨리는
     * 기본값이었다.
     */
    public static ProductStatus fromString(String status) {
        ProductStatus parsed = fromStringOrNull(status);
        if (parsed == null) {
            throw new UnknownEnumValueException(ProductStatus.class, status);
        }
        return parsed;
    }

    /** 모르는 값·빈 값이면 {@code null}. 조회 필터처럼 던지지 않는 쪽이 옳은 자리에서만 쓴다. */
    public static ProductStatus fromStringOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ProductStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
