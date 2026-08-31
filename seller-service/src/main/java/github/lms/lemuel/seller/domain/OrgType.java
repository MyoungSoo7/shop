package github.lms.lemuel.seller.domain;

/**
 * 입점 조직의 종류 — {@code lemuel.organization.created} 의 {@code type} 을 그대로 옮긴 값.
 *
 * <p>파트너 콘솔에서 이 구분은 "매출이 보이느냐" 를 갈랐다. 셀러 백오피스에서는 더 세다 —
 * {@link #CORPORATE} 조직에게는 <b>이 백오피스 자체가 없다.</b> 법인 고객은 우리 몰에서 사는
 * 쪽이지 파는 쪽이 아니라서, 등록할 상품도 출고할 주문도 없다.
 *
 * <p>그래서 여기서는 CORPORATE 를 "빈 화면" 으로 보여주지 않고 {@code 422 NOT_A_SELLER} 로
 * 명확히 거절한다. 빈 목록을 보여 주면 그 사람은 자기 상품이 유실됐다고 생각한다.
 */
public enum OrgType {
    SELLER,
    CORPORATE
}
