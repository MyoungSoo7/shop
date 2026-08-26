package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.OrderPrivacyConsent;

import java.util.List;

/** 주문 시점 동의 이력 조회. */
public interface LoadOrderPrivacyConsentPort {

    /** 이 주문의 동의 전부. 필수·선택을 가리지 않는다 — 거절한 선택 항목도 이력이다. */
    List<OrderPrivacyConsent> findByOrderId(Long orderId);

    /** 이 사람이 남긴 동의를 최근 순으로. 열람 요구권에 답하는 경로다. */
    List<OrderPrivacyConsent> findByUserId(Long userId, int limit);

    /**
     * 특정 문안 버전으로 동의한 이력을 최근 순으로.
     *
     * <p>문안을 고친 운영자가 "옛 버전으로 동의한 사람이 아직 있는가"를 세는 데 쓴다. 그 수가
     * 0 이 아니면 재동의를 받아야 한다는 뜻이다.
     */
    List<OrderPrivacyConsent> findByTermsCodeAndVersion(String termsCode, int termsVersion, int limit);
}
