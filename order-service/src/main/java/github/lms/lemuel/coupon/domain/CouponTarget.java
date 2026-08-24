package github.lms.lemuel.coupon.domain;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;

/**
 * 쿠폰 적용 대상 — <b>enum 기반 Strategy 패턴</b>.
 *
 * <p>"이 쿠폰이 주어진 상품/카테고리에 적용되는가"의 매칭 규칙을 각 enum 상수가 직접 구현한다.
 * 기존에는 {@code targetType} 이 {@code String}("ALL"/"PRODUCT"/"CATEGORY")이라 서비스/도메인에
 * {@code switch} 와 문자열 비교가 흩어져 있었는데(stringly-typed), 그 분기를 타입 자체로 흡수한다.
 * 새 대상(예: SELLER, BRAND)을 추가할 때 호출부 수정 없이 상수만 추가하면 된다(Open/Closed).
 */
public enum CouponTarget {

    /** 전체 적용 — 모든 주문에 매칭. */
    ALL {
        @Override
        public boolean matches(Long targetId, Long productId, Long categoryId) {
            return true;
        }
    },

    /** 특정 상품 — targetId 와 주문 상품이 일치할 때만. */
    PRODUCT {
        @Override
        public boolean matches(Long targetId, Long productId, Long categoryId) {
            return targetId != null && targetId.equals(productId);
        }
    },

    /** 특정 카테고리 — targetId 와 주문 카테고리가 일치할 때만. */
    CATEGORY {
        @Override
        public boolean matches(Long targetId, Long productId, Long categoryId) {
            return targetId != null && targetId.equals(categoryId);
        }
    };

    /** 적용 대상 매칭 — 타입별 규칙. */
    public abstract boolean matches(Long targetId, Long productId, Long categoryId);

    /** ALL 이 아닌 대상은 targetId 가 필수다. */
    public boolean requiresTargetId() {
        return this != ALL;
    }

    /**
     * 사용자 입력 파싱 — null/blank 는 {@link #ALL}, 미지원 값은 예외.
     */
    public static CouponTarget fromInput(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CouponInvariantViolationException("지원하지 않는 쿠폰 적용 대상입니다: " + value);
        }
    }

    /**
     * 저장값 파싱 — null/미지원 값은 {@link #ALL} 로 폴백 (레거시 데이터 호환).
     */
    public static CouponTarget fromStorageOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
