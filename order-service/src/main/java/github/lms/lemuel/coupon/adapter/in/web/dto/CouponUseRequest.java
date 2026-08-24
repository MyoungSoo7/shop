package github.lms.lemuel.coupon.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CouponUseRequest {

    @NotNull(message = "userId는 필수값입니다")
    private Long userId;

    @NotNull(message = "orderId는 필수값입니다")
    private Long orderId;
}