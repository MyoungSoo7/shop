package github.lms.lemuel.marketing.domain;

/**
 * 구매금액을 어느 배송 단계부터 인정할지. 레거시 {@code PRICE_STATUS} 의 1/2/3.
 *
 * <p>{@link AmountBasis} 와 같은 이유로 지금은 설정만 보관한다.
 */
public enum ShippingStatusRequirement {

    /** 배송 시작. */
    SHIPPING_STARTED,

    /** 배송 중. */
    IN_TRANSIT,

    /** 배송 완료. */
    DELIVERED
}
