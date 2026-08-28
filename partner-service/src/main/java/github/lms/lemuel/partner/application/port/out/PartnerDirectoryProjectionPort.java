package github.lms.lemuel.partner.application.port.out;

import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;

/**
 * 조직·구성원 프로젝션 쓰기.
 *
 * <p>전부 upsert 인 이유는 전달이 at-least-once 이기 때문이다. 멱등 테이블
 * ({@code processed_events})이 중복 대부분을 막지만, 그 앞단이 뚫려도 여기서 같은 결과가
 * 나와야 한다 — 3단 방어의 마지막 단이다.
 */
public interface PartnerDirectoryProjectionPort {

    void upsertOrganization(long organizationId, String name, OrgType type, String externalRef,
                            Long sellerId, long ownerUserId);

    void upsertMembership(long membershipId, long organizationId, long userId, MemberRole role);

    /**
     * 멤버십을 REMOVED 로.
     *
     * <p>{@code membershipId} 로 지운다 — (조직, 사용자) 로 지우면 <b>늦게 도착한 옛 탈퇴
     * 이벤트가 재가입으로 새로 생긴 멤버십을 지운다.</b> 그 사람은 화면이 그냥 안 열리게 되고,
     * 원인을 찾을 단서가 아무 데도 남지 않는다.
     */
    void markRemoved(long membershipId);

    void changeRole(long membershipId, MemberRole newRole);
}
