package github.lms.lemuel.partner.application.port.in;

import github.lms.lemuel.partner.domain.PartnerScope;

/**
 * JWT 의 {@code userId} → 볼 수 있는 파트너 조직.
 *
 * <p>모든 조회 컨트롤러가 첫 줄에서 이걸 부른다. 인가의 유일한 출처를 하나로 묶어 둔 것이라,
 * 이 메서드를 우회해 {@code sellerId} 를 만드는 경로가 생기면 그게 곧 IDOR 이다.
 */
public interface ResolvePartnerScopeUseCase {

    /**
     * @param userId JWT 에서 꺼낸 사용자 식별자. <b>요청 파라미터에서 온 값을 넣지 말 것.</b>
     * @throws github.lms.lemuel.partner.domain.exception.PartnerScopeNotFoundException 소속 조직이 없을 때
     */
    PartnerScope resolve(long userId);
}
