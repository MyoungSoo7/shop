package github.lms.lemuel.coupon.application.port.out;

import github.lms.lemuel.coupon.domain.Coupon;

import java.util.List;
import java.util.Optional;

public interface LoadCouponPort {
    Optional<Coupon> findByCode(String code);
    List<Coupon> findAll();
    boolean hasUserUsedCoupon(Long couponId, Long userId);
}