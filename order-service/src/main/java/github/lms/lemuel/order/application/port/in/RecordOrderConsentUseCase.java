package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.OrderPrivacyConsent;

import java.util.List;

/**
 * 주문 시점 동의를 이력으로 남긴다.
 *
 * <p><b>주문 생성과 같은 트랜잭션 안에서 불린다.</b> 별도 호출로 빼면 "주문은 생겼는데 동의는
 * 안 남은" 상태가 생기고, 그건 정확히 이 기능이 없애려는 상태다. 그래서 이 인터페이스에는
 * 트랜잭션을 여는 메서드가 없다 — 부르는 쪽({@code CreateMultiItemOrderService})이 이미 열어 둔
 * 경계 안에서 실행되는 것을 전제로 한다.
 */
public interface RecordOrderConsentUseCase {

    /**
     * 제출된 동의를 검증하고 기록한다.
     *
     * <p>검증은 두 가지다. ① 지금 유효한 문안 중 필수 항목이 모두 동의로 왔는가. ② 제출된 버전이
     * 지금 유효한 버전과 같은가. 하나라도 어긋나면 예외를 던져 <b>주문 자체를 무른다</b> —
     * 동의 없이 주문만 남는 경우를 만들지 않기 위해서다.
     *
     * @param command {@code acceptances} 가 비어 있으면 필수 항목 누락으로 거절된다
     * @return 저장된 이력. 화면에 되돌려 줄 필요는 없지만 테스트와 감사가 이 값을 본다
     */
    List<OrderPrivacyConsent> record(RecordCommand command);

    /**
     * @param userId    동의한 사람. 주문자와 같다
     * @param ipAddress 서버가 관찰한 접속지. 없으면 {@code null} — 보조 증거일 뿐이라 필수가 아니다
     */
    record RecordCommand(Long orderId, Long userId, List<Acceptance> acceptances, String ipAddress) {
    }

    /**
     * 화면에서 올라온 체크 하나.
     *
     * <p>동의 시각을 클라이언트에게 받지 않는 것이 중요하다. 증명하려는 사실을 증명 대상이 스스로
     * 적게 하면 증명이 아니다 — 시각은 서버가 찍는다.
     *
     * @param agreed 선택 항목의 {@code false} 는 "물었고 거절했다"는 기록으로 남는다.
     *               필수 항목의 {@code false} 는 주문을 거절시킨다
     */
    record Acceptance(String termsCode, Integer termsVersion, boolean agreed) {
    }
}
