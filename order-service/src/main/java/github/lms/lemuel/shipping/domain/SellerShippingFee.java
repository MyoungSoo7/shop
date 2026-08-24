package github.lms.lemuel.shipping.domain;

import java.math.BigDecimal;

/**
 * 셀러 1 곳에 대한 배송비 산정 내역.
 *
 * <p>총액만 남기지 않고 내역을 보존하는 이유: 부분 취소로 무료배송 조건이 깨져 배송비가 재부과될 때
 * "원래 왜 0 원이었는지"({@code freeShippingApplied})와 "얼마가 다시 살아나는지"(baseFee)를
 * 근거로 제시해야 CS 가 설명 가능해진다. 정산 대사에서도 셀러별 귀속을 이 내역으로 맞춘다.
 *
 * @param sellerId             판매자
 * @param subtotal             무료배송 판정에 쓰인 셀러 주문 소계
 * @param baseFee              실제 부과된 기본배송비(면제되면 0)
 * @param individualFee        개별배송비 합계
 * @param freeShippingApplied  무료배송 임계 도달로 기본배송비가 면제됐는지
 */
public record SellerShippingFee(Long sellerId, BigDecimal subtotal, BigDecimal baseFee,
                                BigDecimal individualFee, boolean freeShippingApplied) {

    /** 이 셀러에 부과된 배송비 총액. */
    public BigDecimal total() {
        return baseFee.add(individualFee);
    }

    /** 부과 내역이 없는 셀러(정책 미등록·전량 취소 등)의 0 원 내역. */
    static SellerShippingFee zero(Long sellerId) {
        return new SellerShippingFee(sellerId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }
}
