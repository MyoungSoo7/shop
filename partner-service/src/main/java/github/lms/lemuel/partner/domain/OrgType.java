package github.lms.lemuel.partner.domain;

/**
 * 입점 조직의 종류 — {@code lemuel.organization.created} 의 {@code type} 을 그대로 옮긴 값.
 *
 * <p>이 구분이 화면을 가른다. {@link #SELLER} 만 셀러 ID 를 갖고, 셀러 ID 가 있어야 매출이 보인다.
 * {@link #CORPORATE} 는 {@code externalRef} 가 종목코드(stockCode)라 셀러가 아니다 — 대시보드가
 * 비어 있는 것이 정상이고, 화면은 "빈 데이터" 가 아니라 "이 조직은 판매 조직이 아니다" 로 적는다.
 * 둘을 구분하지 않으면 법인 고객이 자기 매출이 유실됐다고 문의하게 된다.
 */
public enum OrgType {
    SELLER,
    CORPORATE
}
