package github.lms.lemuel.order.application.port.in;

import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;

import java.util.List;

/**
 * 동의 문안과 동의 이력을 읽는다.
 *
 * <p>읽기 경로가 있어야 하는 이유는 화면 때문만이 아니다. 개인정보 보호법이 정보주체에게 주는
 * 열람 요구권은 "동의한 내역을 확인할 수 있어야 한다"를 포함한다. 기록만 하고 보여 줄 수 없으면
 * 절반만 한 것이다.
 */
public interface GetPrivacyConsentUseCase {

    /** 결제 화면이 지금 보여 줘야 할 문안. 필수 항목이 앞에 온다. */
    List<PrivacyConsentTerms> currentTerms();

    /** 이 주문에서 무엇에 동의했는가. */
    List<ConsentView> ofOrder(Long orderId);

    /** 이 사람이 언제 무엇에 동의했는가 — 최근 순. 열람 요구권에 답하는 축이다. */
    List<ConsentView> ofUser(Long userId, int limit);

    /**
     * 특정 (문안 코드, 버전) 으로 동의한 이력 — 최근 순.
     *
     * <p>문안을 고친 뒤 "옛 버전으로 동의한 사람이 아직 남아 있는가"를 세는 축이다.
     */
    List<ConsentView> ofTermsVersion(String termsCode, int termsVersion, int limit);

    /**
     * 기록 한 줄과, 그 기록이 지금 문안과 여전히 같은지.
     *
     * @param bodyUnchanged 같은 (코드, 버전) 문안의 전문이 동의 당시와 같은가. {@code false} 면
     *                      버전을 올리지 않고 문장을 고친 것이다 — 그 자체로 조사 대상이라
     *                      화면에서 지우지 않고 그대로 내보낸다
     */
    record ConsentView(OrderPrivacyConsent consent, boolean bodyUnchanged) {
    }
}
