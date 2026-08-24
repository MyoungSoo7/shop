package github.lms.lemuel.shipping.application.port.in;

import github.lms.lemuel.shipping.domain.SafetyNumber;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 수취인 안심번호(가상번호) 배정·회수.
 *
 * <p><b>범위 한계:</b> 번호의 배정·수명·노출만 관리한다. 050 번호가 실번호로 착신 전환되려면
 * 통신사(안심번호 사업자) 연동이 필요하며 여기에는 없다 — 지금 보장하는 것은 "실번호가 API 응답에
 * 노출되지 않는다"까지다.
 */
public interface SafetyNumberUseCase {

    /**
     * 주문에 안심번호를 배정한다. 이미 배정돼 있으면 그 번호를 그대로 돌려준다(멱등).
     *
     * <p>풀이 말랐으면 비어 있는 결과를 준다 — 번호가 없다고 배송 생성을 실패시키지 않는다.
     * 그 경우 실번호가 노출되므로 운영은 풀 고갈을 감시해야 한다(로그 WARN).
     */
    Optional<SafetyNumber> assignForOrder(Long orderId);

    /** 주문에 배정된 번호 조회(응답 마스킹용). */
    Optional<SafetyNumber> findForOrder(Long orderId);

    /** 만료된 번호를 회수해 풀로 되돌린다. @return 회수 건수 */
    int releaseExpired(OffsetDateTime now, int limit);
}
