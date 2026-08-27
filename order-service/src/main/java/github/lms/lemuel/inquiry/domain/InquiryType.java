package github.lms.lemuel.inquiry.domain;

/**
 * 문의 종류.
 *
 * <p>이식 대상이던 레거시는 셋을 <b>서로 다른 테이블</b>로 나눠 두었다(상품문의·1:1문의·상품요청).
 * 세 벌의 목록·상세·답변·삭제 쿼리가 각각 있었고, 셋 다 같은 모양이라 한쪽만 고쳐진 곳이 생겼다 —
 * 예컨대 답변 상태 판정은 상품문의 쪽에만 서브쿼리로 들어가 있다. 여기서는 한 테이블에 종류를
 * 칼럼으로 두고, 종류마다 달라지는 것은 <b>무엇을 함께 요구하는가</b> 하나로 좁힌다.
 */
public enum InquiryType {

    /** 상품에 대한 문의. 어떤 상품인지가 없으면 답변자가 무엇을 보고 답해야 할지 알 수 없다. */
    PRODUCT("상품 문의", true, false),

    /** 주문·배송에 대한 문의. 주문 없이는 배송 상태를 조회할 수 없다. */
    ORDER("주문·배송 문의", false, true),

    /** 그 밖의 1:1 문의. 상품에도 주문에도 매이지 않는다. */
    GENERAL("1:1 문의", false, false);

    private final String label;
    private final boolean productRequired;
    private final boolean orderRequired;

    InquiryType(String label, boolean productRequired, boolean orderRequired) {
        this.label = label;
        this.productRequired = productRequired;
        this.orderRequired = orderRequired;
    }

    public String label() {
        return label;
    }

    public boolean requiresProduct() {
        return productRequired;
    }

    public boolean requiresOrder() {
        return orderRequired;
    }
}
