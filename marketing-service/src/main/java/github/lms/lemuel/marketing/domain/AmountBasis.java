package github.lms.lemuel.marketing.domain;

/**
 * 참여 자격의 구매금액 기준. 레거시 {@code PRICE_TYPE} 의 1/2.
 *
 * <p>⚠ 지금은 캠페인 설정으로 저장만 하고 강제하지 않는다. 금액을 확인하려면 주문 정보가
 * 필요한데 그건 order-service 소유이고, 이 저장소의 불변식상 조회하러 갈 수 없다
 * (서비스 간 연계는 Kafka 이벤트로만). 강제하려면 주문 확정 이벤트를 받아 회원별 구매금액
 * 읽기 모델을 이 서비스에 만들어야 한다 — 별도 작업이고 여기에 섞지 않았다.
 */
public enum AmountBasis {

    /** 실결제금액. */
    ACTUAL_PAID,

    /** 주문금액(할인 전). */
    ORDER_TOTAL
}
