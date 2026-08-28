package github.lms.lemuel.partner.domain;

import github.lms.lemuel.partner.domain.exception.NoSalesScopeException;

/**
 * 로그인한 사람이 <b>무엇을 볼 수 있는가</b>. 이 서비스의 인가는 전부 이 한 타입을 거친다.
 *
 * <h2>왜 타입으로 만드는가</h2>
 * 조회 메서드가 {@code sellerId} 를 {@code Long} 으로 받으면, 그 값이 JWT 에서 유도된 것인지
 * 요청 파라미터에서 온 것인지 시그니처만 봐서는 구분되지 않는다. 그 구분이 안 되는 순간
 * {@code ?sellerId=} 를 그대로 넘기는 코드가 리뷰를 통과한다 — 남의 매출이 열린다(IDOR).
 * 그래서 조회 포트는 {@code long} 이 아니라 이 타입만 받고, 이 타입은
 * {@code partner_members} 조회를 거쳐야만 만들어진다.
 *
 * <h2>sellerId 가 null 일 수 있다</h2>
 * CORPORATE 조직이거나, SELLER 인데 {@code externalRef} 가 숫자가 아니어서 파싱하지 못한
 * 경우다. 후자를 0 이나 -1 로 메우지 않는 이유는 서로 다른 조직이 같은 셀러로 뭉쳐 남의 매출을
 * 보게 되기 때문이다. 매출을 요구하는 경로는 {@link #requireSellerId()} 로 명시적으로 실패한다.
 *
 * @param organizationId 조직 식별자(원본은 order-service 소유)
 * @param organizationName 화면 표시용 조직명
 * @param orgType SELLER / CORPORATE
 * @param sellerId 매출 조회 키. null 이면 이 조직에는 매출 개념이 없다.
 * @param role 조직 내 역할(현재는 표시용 — {@link MemberRole} 참조)
 */
public record PartnerScope(
        long organizationId,
        String organizationName,
        OrgType orgType,
        Long sellerId,
        MemberRole role) {

    /**
     * 매출 조회에 쓸 셀러 ID. 없으면 던진다.
     *
     * <p>null 을 그대로 흘려보내면 조회 조건이 {@code seller_id IS NULL} 이 되어
     * <b>셀러 미할당 결제 전체</b>가 한 조직에 보인다. 조용한 정보 노출이라 반드시 막는다.
     */
    public long requireSellerId() {
        if (sellerId == null) {
            throw new NoSalesScopeException(organizationId, orgType);
        }
        return sellerId;
    }
}
