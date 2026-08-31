package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;

/**
 * 조직·구성원 프로젝션 적재 — {@code lemuel.organization.*} 네 토픽의 도착점.
 *
 * <p>컨슈머가 이 포트만 보게 해서(구체 서비스 금지, ArchUnit), Kafka 페이로드 해석은 어댑터,
 * 프로젝션 규칙은 유스케이스로 갈린다. 순서 독립 규칙(늦게 온 옛 이벤트가 최신을 덮지 않기)은
 * 전부 구현 쪽 책임이다.
 *
 * <p>이 네 이벤트가 이 서비스의 <b>인가 근거 전부</b>다. 파트너 콘솔에서는 이게 조회만 열었지만
 * 여기서는 쓰기를 연다 — 잘못 들어온 멤버십 한 행이 곧 남의 이름으로 등록된 상품이다.
 */
public interface RecordDirectoryUseCase {

    void organizationCreated(OrganizationCreated event);

    void memberJoined(MemberJoined event);

    void memberRemoved(MemberRemoved event);

    void memberRoleChanged(MemberRoleChanged event);

    /**
     * @param externalRef SELLER 면 셀러 참조, CORPORATE 면 종목코드. 숫자가 아니면 셀러 ID 는
     *                    null 로 남는다 — 0/-1 로 메우면 서로 다른 조직이 한 셀러로 뭉친다.
     */
    record OrganizationCreated(long organizationId, String name, OrgType type, String externalRef,
                               long ownerUserId) {
    }

    record MemberJoined(long membershipId, long organizationId, long userId, MemberRole role) {
    }

    record MemberRemoved(long membershipId, long organizationId, long userId) {
    }

    record MemberRoleChanged(long membershipId, long organizationId, long userId, MemberRole newRole) {
    }
}
