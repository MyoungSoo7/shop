package github.lms.lemuel.coupon.adapter.in.web.dto;

import github.lms.lemuel.coupon.domain.CouponType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class CouponCreateRequest {

    @NotBlank(message = "쿠폰 코드는 필수입니다.")
    private String code;

    @NotNull(message = "쿠폰 타입은 필수입니다.")
    private CouponType type;

    @NotNull(message = "할인 값은 필수입니다.")
    @Positive(message = "할인 값은 0보다 커야 합니다.")
    private BigDecimal discountValue;

    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Positive(message = "최대 사용 횟수는 1 이상이어야 합니다.")
    private int maxUses = 1;

    private BigDecimal maxDiscountAmount;

    private String targetType = "ALL";

    private Long targetId;

    private LocalDateTime startsAt;

    private LocalDateTime expiresAt;
}
