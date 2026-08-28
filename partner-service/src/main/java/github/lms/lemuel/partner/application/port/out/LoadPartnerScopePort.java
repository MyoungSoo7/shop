package github.lms.lemuel.partner.application.port.out;

import github.lms.lemuel.partner.application.port.dto.PartnerMemberView;
import github.lms.lemuel.partner.domain.PartnerScope;

import java.util.List;
import java.util.Optional;

/**
 * 인가 조회 — {@code partner_members}(활성) ⋈ {@code partners}.
 *
 * <p>이 포트에 "조직 ID 로 스코프 얻기" 같은 메서드를 두지 않는 것이 의도다. 그런 메서드가 있으면
 * 요청에서 받은 조직 ID 로 스코프를 만드는 코드가 생기고, 그게 곧 IDOR 이다. 입구는
 * {@link #findByUserId(long)} 하나뿐이며 인자는 JWT 에서만 온다.
 */
public interface LoadPartnerScopePort {

    Optional<PartnerScope> findByUserId(long userId);

    List<PartnerMemberView> findActiveMembers(long organizationId);
}
