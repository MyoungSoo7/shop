package github.lms.lemuel.shipping.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 배송비 산정기 — 셀러별 조건부 무료배송 + 상품 개별배송비.
 *
 * <p><b>규칙</b> (SSG B2E 실무 규칙 이식):
 * <ol>
 *   <li>라인을 셀러로 묶고, 셀러별 소계 = 그 셀러 라인들의 {@code lineAmount} 합.</li>
 *   <li>셀러 라인 중 {@link ShippingChargeType#SELLER_BASE} 가 하나라도 있으면 그 셀러의
 *       기본배송비를 <b>1 회</b> 부과한다. 단 소계가 무료배송 임계 이상이면 면제.</li>
 *   <li>{@link ShippingChargeType#INDIVIDUAL} 라인은 무료배송 조건과 무관하게 라인마다 부과한다.</li>
 *   <li>배송비 정책이 등록되지 않은 셀러는 기본배송비 0 — 정책이 없다는 이유로 고객에게 청구하지 않는다.</li>
 * </ol>
 *
 * <p><b>왜 순수 계산기인가:</b> 레거시는 이 판정 전체가 MyBatis SQL 의 윈도우 함수 + {@code CASE} 안에
 * 있었고, Java 는 셀러 그룹의 <i>첫 행</i> 값을 집어 쓰는 방식이라 "셀러당 1 회"가 정렬 우연에
 * 기대고 있었다. 여기서는 그 우연을 규칙으로 못박고, 경계값(임계와 정확히 같은 금액)·중복 라인·
 * 정책 미등록을 전부 단위 테스트로 고정한다.
 *
 * <p><b>금액 기준:</b> 무료배송 판정 소계는 <b>쿠폰 할인 전</b> 라인 금액 합이다. 쿠폰은 주문 전체
 * 단위로 적용돼 셀러별 안분 규칙이 따로 필요한데, 그 안분을 배송비가 좌우하게 두면 "쿠폰을 썼더니
 * 배송비가 생겼다"는 역진성이 생긴다. 레거시도 동일하게 할인 전 판매가 합({@code cmmTotalprice})을 썼다.
 */
public final class ShippingFeeCalculator {

    private ShippingFeeCalculator() {}

    /**
     * 배송비를 산정한다.
     *
     * @param lines    배송비 산정 대상 라인(취소된 라인은 호출부가 미리 제외한다)
     * @param policies 셀러 배송비 정책(sellerId → 정책). 없는 셀러는 기본배송비 0
     */
    public static ShippingFeeAssessment assess(List<ShippingLine> lines,
                                               Map<Long, SellerShippingPolicy> policies) {
        if (lines == null || lines.isEmpty()) {
            return ShippingFeeAssessment.none();
        }
        Map<Long, SellerShippingPolicy> policyMap = policies == null ? Map.of() : policies;

        Map<Long, List<ShippingLine>> bySeller = new LinkedHashMap<>();
        for (ShippingLine line : lines) {
            bySeller.computeIfAbsent(line.sellerId(), key -> new ArrayList<>()).add(line);
        }

        List<SellerShippingFee> breakdown = new ArrayList<>(bySeller.size());
        BigDecimal totalFee = BigDecimal.ZERO;

        for (Map.Entry<Long, List<ShippingLine>> entry : bySeller.entrySet()) {
            Long sellerId = entry.getKey();
            List<ShippingLine> sellerLines = entry.getValue();

            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal individualFee = BigDecimal.ZERO;
            boolean hasBaseLine = false;
            for (ShippingLine line : sellerLines) {
                subtotal = subtotal.add(line.lineAmount());
                individualFee = individualFee.add(line.individualCharge());
                hasBaseLine = hasBaseLine || line.chargeType().chargesSellerBase();
            }

            SellerShippingPolicy policy = policyMap.get(sellerId);
            BigDecimal baseFee = BigDecimal.ZERO;
            boolean freeApplied = false;
            if (hasBaseLine && policy != null) {
                baseFee = policy.baseFeeFor(subtotal);
                freeApplied = policy.qualifiesForFreeShipping(subtotal);
            }

            SellerShippingFee fee =
                    new SellerShippingFee(sellerId, subtotal, baseFee, individualFee, freeApplied);
            breakdown.add(fee);
            totalFee = totalFee.add(fee.total());
        }

        breakdown.sort(Comparator.comparing(SellerShippingFee::sellerId));
        return new ShippingFeeAssessment(totalFee, breakdown);
    }
}
