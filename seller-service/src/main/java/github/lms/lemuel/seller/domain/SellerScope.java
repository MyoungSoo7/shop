package github.lms.lemuel.seller.domain;

import github.lms.lemuel.seller.domain.exception.InsufficientSellerRoleException;
import github.lms.lemuel.seller.domain.exception.NotASellerException;

/**
 * 로그인한 사람이 <b>무엇을 할 수 있는가</b>. 이 서비스의 인가는 전부 이 한 타입을 거친다.
 *
 * <h2>왜 타입으로 만드는가</h2>
 * 메서드가 {@code sellerId} 를 {@code Long} 으로 받으면, 그 값이 JWT 에서 유도된 것인지 요청
 * 파라미터에서 온 것인지 시그니처만 봐서는 구분되지 않는다. 그 구분이 안 되는 순간
 * {@code ?sellerId=} 를 그대로 넘기는 코드가 리뷰를 통과한다.
 *
 * <p><b>파트너 콘솔보다 이 서비스에서 그 사고가 더 나쁘다.</b> 거기서는 남의 매출을 <i>보는</i>
 * 것이었지만, 여기서는 남의 이름으로 상품을 등록하고 남의 주문에 송장을 찍는 것이다. 읽기 IDOR 은
 * 정보 노출이고 쓰기 IDOR 은 위조다. 그래서 포트는 {@code long} 이 아니라 이 타입만 받고,
 * 이 타입은 {@code seller_members} 조회를 거쳐야만 만들어진다.
 *
 * @param organizationId 조직 식별자(원본은 order-service 소유)
 * @param organizationName 화면 표시용 조직명
 * @param orgType SELLER / CORPORATE
 * @param sellerId 셀러 식별자. null 이면 이 조직은 파는 쪽이 아니다.
 * @param role 조직 내 역할 — {@link MemberRole#canSubmit()} 이 실제 통제에 쓰인다
 */
public record SellerScope(
        long organizationId,
        String organizationName,
        OrgType orgType,
        Long sellerId,
        MemberRole role) {

    /**
     * 이 백오피스의 모든 조회·쓰기가 매이는 셀러 ID. 없으면 던진다.
     *
     * <p>null 을 그대로 흘려보내면 조건이 {@code seller_id IS NULL} 이 되어 셀러 미할당 데이터
     * 전체가 한 조직에 열린다.
     */
    public long requireSellerId() {
        if (sellerId == null) {
            throw new NotASellerException(organizationId, orgType);
        }
        return sellerId;
    }

    /**
     * 밖으로 내보내는 행위(신청서 제출·송장 등록)를 하기 전에 부른다.
     *
     * <p>셀러 ID 확인까지 함께 하는 것은 의도다 — 두 검사를 따로 두면 호출부에서 한쪽만 부르는
     * 코드가 반드시 생기고, 어느 쪽이 빠졌는지는 시그니처에 드러나지 않는다.
     */
    public long requireSubmitPermission() {
        long id = requireSellerId();
        if (!role.canSubmit()) {
            throw new InsufficientSellerRoleException(role);
        }
        return id;
    }
}
