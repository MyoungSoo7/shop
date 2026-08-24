package github.lms.lemuel.shipping.domain;

/**
 * 상품 배송비 부과 유형 — 상품마다 하나를 갖는다.
 *
 * <p>SSG B2E 실무 스키마({@code TBL_PRODUCT.SHIPCHARGE_TYPE}) 의 세 갈래를 이름 있는 타입으로 옮긴 것.
 * 레거시는 {@code 1/2/3} 숫자 코드였고, 그 숫자를 읽으려면 SQL 의 {@code CASE} 를 따라가야 했다.
 *
 * <ul>
 *   <li>{@link #FREE} — 무조건 무료. 셀러 정책·주문 금액과 무관하게 배송비에 기여하지 않는다.</li>
 *   <li>{@link #SELLER_BASE} — 셀러 기본배송비 대상. 셀러별로 <b>1 회만</b> 부과되고,
 *       그 셀러의 주문 소계가 무료배송 임계 이상이면 면제된다(조건부 무료).</li>
 *   <li>{@link #INDIVIDUAL} — 상품 개별배송비. 무료배송 조건과 <b>무관하게</b> 라인마다 부과된다
 *       (냉장/설치/대형가구처럼 묶음배송이 불가능한 상품).</li>
 * </ul>
 */
public enum ShippingChargeType {
    /** 무조건 무료배송. */
    FREE,
    /** 셀러 기본배송비 — 셀러 단위 1 회 부과, 무료배송 임계 이상이면 면제. */
    SELLER_BASE,
    /** 상품 개별배송비 — 무료 조건과 무관하게 라인마다 부과. */
    INDIVIDUAL;

    /** 셀러 기본배송비 부과 대상인지. */
    public boolean chargesSellerBase() {
        return this == SELLER_BASE;
    }

    /** 라인 개별배송비 부과 대상인지. */
    public boolean chargesIndividual() {
        return this == INDIVIDUAL;
    }
}
