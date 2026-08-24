package github.lms.lemuel.coupon.adapter.in.web.dto;

import java.math.BigDecimal;

public record CouponValidateResponse(
        boolean valid,
        String message,
        BigDecimal discountAmount,
        BigDecimal finalAmount
) {}