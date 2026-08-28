package github.lms.lemuel.partner.domain;

/**
 * 셀러 등급 — {@code lemuel.seller.tier_changed} 의 {@code newTier}.
 *
 * <p>★ 등급은 비소급이다(ADR 0031 · ADR 0014 §4). 이 값은 "지금 등급" 일 뿐이고, 과거 매출의
 * 정산 조건은 결제 이벤트가 함께 실어 온 시점 등급({@code partner_sales.seller_tier})이 정한다.
 * 현재 등급으로 과거를 다시 계산하면 이미 정산된 금액이 소급해 바뀐다.
 */
public enum SellerTier {
    NORMAL,
    VIP,
    STRATEGIC
}
