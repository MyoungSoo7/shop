package github.lms.lemuel.shipping.domain;

import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;

import java.math.BigDecimal;

/**
 * 셀러 배송비 정책 — 기본배송비와 무료배송 임계.
 *
 * <p>SSG B2E 실무 스키마({@code TBL_SELLMEMBER.MBSHIPMNY / MBSHIPLIMIT}) 대응. 레거시는 이 판정을
 * SQL 의 윈도우 함수 {@code CASE WHEN SUM(...) OVER (PARTITION BY SELLID) >= MBSHIPLIMIT} 안에 두어
 * 단위 테스트가 불가능했다. 여기서는 순수 도메인으로 끌어올려 경계값(임계와 정확히 같은 금액)까지
 * 테스트로 못박는다.
 *
 * <p>{@code freeThreshold} 가 {@code null} 이면 무료배송 조건이 없다는 뜻 — 금액과 무관하게 항상
 * 기본배송비를 부과한다. 0 으로 두면 "언제나 무료"가 되므로 둘은 다른 의미다.
 */
public final class SellerShippingPolicy {

    private final Long sellerId;
    private final BigDecimal baseFee;
    private final BigDecimal freeThreshold;

    private SellerShippingPolicy(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        this.sellerId = sellerId;
        this.baseFee = baseFee;
        this.freeThreshold = freeThreshold;
    }

    public static SellerShippingPolicy of(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        validate(sellerId, baseFee, freeThreshold);
        return new SellerShippingPolicy(sellerId, baseFee, freeThreshold);
    }

    /** 영속 레코드 복원 — 검증은 저장 시점에 이미 통과했으므로 그대로 재구성한다. */
    public static SellerShippingPolicy rehydrate(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        return new SellerShippingPolicy(sellerId, baseFee, freeThreshold);
    }

    private static void validate(Long sellerId, BigDecimal baseFee, BigDecimal freeThreshold) {
        if (sellerId == null) {
            throw new ShipmentInvariantViolationException("배송비 정책의 sellerId 는 필수입니다");
        }
        if (baseFee == null || baseFee.signum() < 0) {
            throw new ShipmentInvariantViolationException("기본배송비는 0 이상이어야 합니다: " + baseFee);
        }
        if (freeThreshold != null && freeThreshold.signum() < 0) {
            throw new ShipmentInvariantViolationException("무료배송 임계는 0 이상이어야 합니다: " + freeThreshold);
        }
    }

    /** 셀러 주문 소계가 무료배송 임계에 도달했는지 — 경계(임계와 같은 금액)는 무료다. */
    public boolean qualifiesForFreeShipping(BigDecimal sellerSubtotal) {
        if (freeThreshold == null) {
            return false;
        }
        BigDecimal subtotal = sellerSubtotal == null ? BigDecimal.ZERO : sellerSubtotal;
        return subtotal.compareTo(freeThreshold) >= 0;
    }

    /** 이 셀러 소계에 실제로 부과될 기본배송비. */
    public BigDecimal baseFeeFor(BigDecimal sellerSubtotal) {
        return qualifiesForFreeShipping(sellerSubtotal) ? BigDecimal.ZERO : baseFee;
    }

    public Long getSellerId() { return sellerId; }
    public BigDecimal getBaseFee() { return baseFee; }
    public BigDecimal getFreeThreshold() { return freeThreshold; }
}
