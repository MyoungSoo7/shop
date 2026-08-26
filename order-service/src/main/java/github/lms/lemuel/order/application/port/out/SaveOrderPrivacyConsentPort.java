package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.OrderPrivacyConsent;

import java.util.List;

/** 주문 시점 동의 이력 저장. */
public interface SaveOrderPrivacyConsentPort {

    /**
     * 한 주문의 동의를 <b>한 번에</b> 저장한다.
     *
     * <p>한 건씩 저장하는 메서드를 두지 않는 이유는, 그러면 "일부만 저장된 주문"이 문법적으로
     * 가능해지기 때문이다. 필수 3건 중 2건만 남은 이력은 없느니만 못하다 — 있는 것처럼 보이는데
     * 근거로는 못 쓴다.
     */
    List<OrderPrivacyConsent> saveAll(List<OrderPrivacyConsent> consents);
}
