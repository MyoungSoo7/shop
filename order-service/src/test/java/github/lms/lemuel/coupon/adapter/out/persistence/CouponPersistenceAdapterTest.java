package github.lms.lemuel.coupon.adapter.out.persistence;

import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.CouponType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Coupon 영속 매핑 회귀 테스트.
 *
 * <p>정률 쿠폰 상한 {@code maxDiscountAmount} 가 도메인 ↔ 엔티티 양방향 매핑에서 보존되는지 검증한다.
 * 과거 엔티티/어댑터에 필드가 빠져 DB 왕복 시 상한이 유실(null=무제한)되던 버그의 재발 방지.
 */
@ExtendWith(MockitoExtension.class)
class CouponPersistenceAdapterTest {

    @Mock SpringDataCouponJpaRepository couponRepository;
    @Mock SpringDataCouponUsageJpaRepository usageRepository;

    @Test
    @DisplayName("save: 정률 쿠폰 상한(maxDiscountAmount) 이 저장→복원 후에도 보존된다")
    void save_preservesMaxDiscountAmount() {
        CouponPersistenceAdapter adapter = new CouponPersistenceAdapter(couponRepository, usageRepository);
        // 저장된 엔티티를 그대로 돌려주어 toEntity→toDomain 왕복을 재현
        when(couponRepository.save(any(CouponJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Coupon coupon = Coupon.create("SAVE10", CouponType.PERCENTAGE, new BigDecimal("10"),
                BigDecimal.ZERO, new BigDecimal("5000"), 100,
                LocalDateTime.now().plusDays(30));

        Coupon saved = adapter.save(coupon);

        assertThat(saved.getMaxDiscountAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("findByCode: 컬럼의 max_discount_amount 가 도메인으로 복원된다")
    void findByCode_restoresMaxDiscountAmount() {
        CouponPersistenceAdapter adapter = new CouponPersistenceAdapter(couponRepository, usageRepository);

        CouponJpaEntity entity = new CouponJpaEntity();
        entity.setId(1L);
        entity.setCode("SAVE10");
        entity.setType("PERCENTAGE");
        entity.setDiscountValue(new BigDecimal("10"));
        entity.setMinOrderAmount(BigDecimal.ZERO);
        entity.setMaxDiscountAmount(new BigDecimal("5000"));
        entity.setMaxUses(100);
        entity.setUsedCount(0);
        entity.setTargetType("ALL");
        entity.setActive(true);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(entity));

        Coupon coupon = adapter.findByCode("SAVE10").orElseThrow();

        assertThat(coupon.getMaxDiscountAmount()).isEqualByComparingTo("5000");
        // 상한 미만 주문엔 정률 그대로(1,000), 상한 초과 주문엔 상한(5,000) 으로 캡
        assertThat(coupon.calculateDiscount(new BigDecimal("10000"))).isEqualByComparingTo("1000");
        assertThat(coupon.calculateDiscount(new BigDecimal("1000000"))).isEqualByComparingTo("5000");
    }
}
