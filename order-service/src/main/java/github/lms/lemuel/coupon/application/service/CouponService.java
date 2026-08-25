package github.lms.lemuel.coupon.application.service;

import github.lms.lemuel.coupon.application.port.in.CouponUseCase;
import github.lms.lemuel.coupon.application.port.out.LoadCouponPort;
import github.lms.lemuel.coupon.application.port.out.SaveCouponPort;
import github.lms.lemuel.coupon.domain.Coupon;
import github.lms.lemuel.coupon.domain.DiscountTargetLine;
import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;
import github.lms.lemuel.coupon.domain.exception.InvalidCouponStateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CouponService implements CouponUseCase {

    private final LoadCouponPort loadCouponPort;
    private final SaveCouponPort saveCouponPort;
    /** 쿠폰 기간 판정의 시간 소스 — KST({@code TimeConfig}). 도메인에 시각을 값으로 넘긴다. */
    private final Clock clock;

    @Override
    public Coupon createCoupon(CreateCouponCommand command) {
        Coupon coupon = Coupon.create(
                command.code(),
                command.type(),
                command.discountValue(),
                command.minOrderAmount(),
                command.maxDiscountAmount(),
                command.maxUses(),
                command.expiresAt()
        );
        coupon.configureTarget(command.targetType(), command.targetId());
        coupon.configurePeriod(command.startsAt(), command.expiresAt());
        return saveCouponPort.save(coupon);
    }

    @Override
    @Transactional(readOnly = true)
    public ValidateResult validateCoupon(String code, Long userId, List<DiscountTargetLine> lines) {
        BigDecimal subtotal = lines.stream()
                .map(DiscountTargetLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Coupon coupon = loadCouponPort.findByCode(code.toUpperCase().trim())
                .orElse(null);

        if (coupon == null) {
            return invalid("존재하지 않는 쿠폰 코드입니다.", subtotal);
        }

        // 이미 사용한 쿠폰인지 확인
        if (loadCouponPort.hasUserUsedCoupon(coupon.getId(), userId)) {
            return invalid("이미 사용한 쿠폰입니다.", subtotal);
        }

        // 최소 주문 금액은 소계 전체 기준이다 — "3만원 이상 구매 시 A상품 10%" 가 흔한 형태다.
        try {
            coupon.validate(subtotal, LocalDateTime.now(clock));
        } catch (InvalidCouponStateException e) {
            return invalid(e.getMessage(), subtotal);
        }

        // 할인은 대상에 맞는 라인 합만 깎는다. 여기가 이전에 소계 전체였고, 그래서 상품 전용
        // 쿠폰이 장바구니를 통째로 깎았다.
        BigDecimal eligible = coupon.eligibleBase(lines);
        if (eligible.signum() == 0) {
            // 0 원 할인으로 통과시키면 고객은 쿠폰이 먹혔다고 믿은 채 사용 이력만 소모한다.
            return invalid("이 쿠폰을 사용할 수 있는 상품이 장바구니에 없습니다.", subtotal);
        }

        BigDecimal discount = coupon.calculateDiscount(eligible);
        BigDecimal finalAmount = subtotal.subtract(discount);

        log.info("쿠폰 검증 성공: code={}, userId={}, target={}, 소계={}, 대상금액={}, discount={}",
                code, userId, coupon.getTargetType(), subtotal, eligible, discount);
        return new ValidateResult(true, "쿠폰이 적용되었습니다.", discount, finalAmount, eligible, coupon);
    }

    private static ValidateResult invalid(String message, BigDecimal subtotal) {
        return new ValidateResult(false, message, BigDecimal.ZERO, subtotal, BigDecimal.ZERO, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ValidateResult> getAvailableCoupons(Long userId, BigDecimal orderAmount,
                                                    Long productId, Long categoryId) {
        // 상품 상세에서 "이 상품에 쓸 수 있는 쿠폰" 을 보여주는 목록 경로 — 장바구니가 아니라
        // 상품 하나가 문맥이라, 그 상품 한 줄짜리 장바구니로 검증한다.
        List<DiscountTargetLine> singleLine =
                List.of(new DiscountTargetLine(productId, categoryId, orderAmount));
        return loadCouponPort.findAll().stream()
                .filter(c -> c.appliesTo(productId, categoryId))
                .map(c -> validateCoupon(c.getCode(), userId, singleLine))
                .filter(ValidateResult::valid)
                .toList();
    }

    @Override
    public void useCoupon(String code, Long userId, Long orderId) {
        Coupon coupon = loadCouponPort.findByCode(code.toUpperCase().trim())
                .orElseThrow(() -> new CouponInvariantViolationException("존재하지 않는 쿠폰: " + code));

        if (!saveCouponPort.incrementUsageIfAvailable(coupon.getId())) {
            throw new InvalidCouponStateException("쿠폰 사용 한도를 초과했습니다.");
        }

        // 1인 1매 한도는 coupon_usages(coupon_id, user_id) UNIQUE 제약으로 강제한다.
        // validateCoupon 의 hasUserUsedCoupon 체크는 동시 요청을 막지 못하는 소프트 체크이므로,
        // 같은 사용자의 동시 사용 시 두 번째 INSERT 가 제약 위반 → 전체 트랜잭션 롤백(used_count 증가도 취소)
        // → 사용자에게 멱등하게 "이미 사용한 쿠폰" 으로 응답한다.
        try {
            saveCouponPort.recordUsage(coupon.getId(), userId, orderId);
        } catch (DataIntegrityViolationException e) {
            log.warn("쿠폰 중복 사용 차단: code={}, userId={}", code, userId);
            throw new InvalidCouponStateException("이미 사용한 쿠폰입니다.", e);
        }

        log.info("쿠폰 사용 완료: code={}, userId={}, orderId={}", code, userId, orderId);
    }

    /**
     * 주문 취소·환불 시 쿠폰 회수.
     *
     * <p>순서가 중요하다: 사용 이력 무효화가 먼저다. 무효화에 성공한 쿠폰에 대해서만 사용 횟수를
     * 깎아야 중복 호출이 한도를 부풀리지 않는다(무효화 자체가 "이번에 처음 되돌렸다"는 증거).
     *
     * <p>사용 횟수 감소가 실패해도(이미 0 — 데이터 이상) 취소 트랜잭션을 깨뜨리지 않는다.
     * 되돌릴 카운터가 없다는 것은 고객 손해가 아니고, 여기서 예외를 던지면 <b>환불이 막힌다</b>.
     */
    @Override
    public int restoreCouponsForOrder(Long orderId, String reason) {
        if (orderId == null) {
            return 0;
        }
        List<Long> couponIds = saveCouponPort.revokeUsagesForOrder(orderId, reason);
        int restored = 0;
        for (Long couponId : couponIds) {
            if (saveCouponPort.decrementUsage(couponId)) {
                restored++;
            } else {
                log.warn("쿠폰 사용 횟수 회수 실패(이미 0): couponId={}, orderId={}", couponId, orderId);
            }
        }
        if (restored > 0) {
            log.info("쿠폰 회수 완료: orderId={}, 쿠폰={}건, reason={}", orderId, restored, reason);
        }
        return restored;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> getAllCoupons() {
        return loadCouponPort.findAll();
    }
}
