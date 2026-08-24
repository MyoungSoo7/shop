package github.lms.lemuel.coupon.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 쿠폰 사용 이력.
 *
 * <p><b>1인 1매 제약은 여기 선언하지 않는다.</b> "살아 있는 사용에만" 걸려야 하는 부분 UNIQUE
 * 인덱스({@code uq_coupon_usage_user_active ... WHERE revoked_at IS NULL}, 마이그레이션
 * {@code V20260821140000})라 JPA {@code @UniqueConstraint} 로는 표현할 수 없다. 전체 UNIQUE 를
 * 선언하면 주문 취소로 돌려받은 쿠폰을 다시 쓸 때 제약 위반으로 막힌다.
 */
@Entity
@Table(name = "coupon_usages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CouponUsageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "used_at", nullable = false)
    private LocalDateTime usedAt;

    /** 무효화 시각 — NULL 이면 유효한 사용. 행을 지우지 않는 이유는 "썼다가 돌려받았다"가 이력이기 때문. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    @PrePersist
    protected void onCreate() {
        if (usedAt == null) usedAt = LocalDateTime.now();
    }
}
