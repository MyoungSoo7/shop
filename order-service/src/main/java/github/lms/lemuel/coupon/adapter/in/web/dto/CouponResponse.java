package github.lms.lemuel.coupon.adapter.in.web.dto;

import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CouponResponse(
        Long id,
        String code,
        CouponType type,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        int maxUses,
        int usedCount,
        String targetType,
        Long targetId,
        LocalDateTime startsAt,
        LocalDateTime expiresAt,
        boolean isActive,
        LocalDateTime createdAt
) {
    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderAmount(),
                coupon.getMaxDiscountAmount(),
                coupon.getMaxUses(),
                coupon.getUsedCount(),
                coupon.getTargetType().name(),
                coupon.getTargetId(),
                coupon.getStartsAt(),
                coupon.getExpiresAt(),
                coupon.isActive(),
                coupon.getCreatedAt()
        );
    }
}
