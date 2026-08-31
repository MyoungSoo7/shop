package github.lms.lemuel.seller.application.port.out;

import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;

/**
 * 조직·구성원 프로젝션 쓰기.
 *
 * <p>전부 upsert 인 이유는 전달이 at-least-once 이기 때문이다. 멱등 테이블
 * ({@code processed_events})이 중복 대부분을 막지만, 그 앞단이 뚫려도 여기서 같은 결과가
 * 나와야 한다 — 3단 방어의 마지막 단이다.
 */
public interface SellerDirectoryProjectionPort {

    void upsertOrganization(long organizationId, String name, OrgType type, String externalRef,
                            Long sellerId, long ownerUserId);

    void upsertMembership(long membershipId, long organizationId, long userId, MemberRole role);

    /**
     * 멤버십을 REMOVED 로.
     *
     * <p>{@code membershipId} 로 지운다 — (조직, 사용자) 로 지우면 <b>늦게 도착한 옛 탈퇴
     * 이벤트가 재가입으로 새로 생긴 멤버십을 지운다.</b> 파트너 콘솔에서는 그 사람의 화면이 안
     * 열리는 데서 끝났지만, 여기서는 상품 등록·송장 등록 권한이 함께 사라진다.
     */
    void markRemoved(long membershipId);

    void changeRole(long membershipId, MemberRole newRole);
}
