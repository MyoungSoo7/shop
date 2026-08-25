package github.lms.lemuel.coupon.domain;

import github.lms.lemuel.coupon.domain.exception.CouponInvariantViolationException;

import java.math.BigDecimal;

/**
 * 쿠폰이 적용될지 판정할 <b>장바구니 한 줄</b> — 쿠폰 도메인이 카탈로그를 모른 채 대상 매칭을 하기 위한 값.
 *
 * <p>쿠폰 모듈은 상품·카테고리 테이블을 읽지 않는다. 라인을 만드는 쪽(주문)이 상품 마스터에서
 * 해석한 값을 여기 담아 넘긴다 — 그래서 "미리보기에 보이는 할인"과 "결제에 적용되는 할인"이
 * 같은 입력을 보게 된다.
 *
 * @param productId  이 줄의 상품 ID. 대상을 특정할 수 없는 호출(금액만 아는 레거시 경로)이면 {@code null}
 * @param categoryId 그 상품이 속한 카테고리 ID. 없으면 {@code null}
 * @param amount     이 줄의 금액(정가 × 수량). 쿠폰 할인 <b>전</b> 금액이다
 */
public record DiscountTargetLine(Long productId, Long categoryId, BigDecimal amount) {

    public DiscountTargetLine {
        if (amount == null || amount.signum() < 0) {
            throw new CouponInvariantViolationException("라인 금액은 0 이상이어야 합니다: " + amount);
        }
    }
}
