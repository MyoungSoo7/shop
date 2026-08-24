package github.lms.lemuel.coupon.adapter.out.persistence;

import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponTarget;
import github.lms.lemuel.coupon.domain.CouponType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CouponPersistenceAdapter implements LoadCouponPort, SaveCouponPort {

    private final SpringDataCouponJpaRepository couponRepository;
    private final SpringDataCouponUsageJpaRepository usageRepository;

    @Override
    public Optional<Coupon> findByCode(String code) {
        return couponRepository.findByCode(code).map(this::toDomain);
    }

    @Override
    public List<Coupon> findAll() {
        return couponRepository.findAll().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean hasUserUsedCoupon(Long couponId, Long userId) {
        return usageRepository.existsByCouponIdAndUserIdAndRevokedAtIsNull(couponId, userId);
    }

    @Override
    public Coupon save(Coupon coupon) {
        CouponJpaEntity entity = toEntity(coupon);
        CouponJpaEntity saved = couponRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public boolean incrementUsageIfAvailable(Long couponId) {
        return couponRepository.incrementUsedCountIfAvailable(couponId) > 0;
    }

    @Override
    public void recordUsage(Long couponId, Long userId, Long orderId) {
        CouponUsageJpaEntity usage = new CouponUsageJpaEntity();
        usage.setCouponId(couponId);
        usage.setUserId(userId);
        usage.setOrderId(orderId);
        usageRepository.save(usage);
    }

    /**
     * 무효화 대상 쿠폰 id 를 <b>먼저 읽고</b> 무효화한다 — 순서를 뒤집으면 UPDATE 가 조건을 지워
     * 되돌릴 쿠폰 목록을 영영 못 찾는다. 두 번째 호출은 UPDATE 영향 행이 0 이라 빈 목록이 되어 멱등하다.
     */
    @Override
    public List<Long> revokeUsagesForOrder(Long orderId, String reason) {
        List<Long> couponIds = usageRepository.findActiveCouponIdsByOrderId(orderId);
        if (couponIds.isEmpty()) {
            return List.of();
        }
        int revoked = usageRepository.revokeByOrderId(orderId, truncateReason(reason));
        return revoked > 0 ? couponIds : List.of();
    }

    @Override
    public boolean decrementUsage(Long couponId) {
        return couponRepository.decrementUsedCount(couponId) > 0;
    }

    /** revoke_reason 은 VARCHAR(200) — 긴 취소 사유가 저장 시점 예외로 취소 트랜잭션을 깨뜨리지 않게 자른다. */
    private static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 200 ? reason : reason.substring(0, 200);
    }

    private CouponJpaEntity toEntity(Coupon domain) {
        CouponJpaEntity entity = new CouponJpaEntity();
        entity.setId(domain.getId());
        entity.setCode(domain.getCode());
        entity.setType(domain.getType().name());
        entity.setDiscountValue(domain.getDiscountValue());
        entity.setMinOrderAmount(domain.getMinOrderAmount());
        entity.setMaxDiscountAmount(domain.getMaxDiscountAmount());
        entity.setMaxUses(domain.getMaxUses());
        entity.setUsedCount(domain.getUsedCount());
        entity.setTargetType(domain.getTargetType().name());
        entity.setTargetId(domain.getTargetId());
        entity.setStartsAt(domain.getStartsAt());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setActive(domain.isActive());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private Coupon toDomain(CouponJpaEntity entity) {
        return Coupon.rehydrate()
                .id(entity.getId())
                .code(entity.getCode())
                .type(CouponType.valueOf(entity.getType()))
                .discountValue(entity.getDiscountValue())
                .minOrderAmount(entity.getMinOrderAmount())
                .maxDiscountAmount(entity.getMaxDiscountAmount())
                .maxUses(entity.getMaxUses())
                .usedCount(entity.getUsedCount())
                .targetType(CouponTarget.fromStorageOrDefault(entity.getTargetType()))
                .targetId(entity.getTargetId())
                .startsAt(entity.getStartsAt())
                .expiresAt(entity.getExpiresAt())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
