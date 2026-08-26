package github.lms.lemuel.order.application.port.out;

import github.lms.lemuel.order.domain.PrivacyConsentTerms;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 동의 문안 카탈로그 조회. */
public interface LoadPrivacyConsentTermsPort {

    /**
     * 주어진 시각에 유효한 문안 전부.
     *
     * <p>"현행"을 시각으로 묻는 이유는 두 가지다. 하나는 문안 교체를 예약해 둘 수 있어야 하기
     * 때문이고, 다른 하나는 테스트가 시계를 고정할 수 있어야 하기 때문이다.
     */
    List<PrivacyConsentTerms> findEffectiveAt(LocalDateTime at);

    /**
     * 지난 문안까지 포함해 (코드, 버전)으로 하나 찾는다.
     *
     * <p>이력 화면이 "동의 당시 문안이 지금도 같은지" 대조할 때 쓴다. 유효기간이 지난 문안도
     * 찾을 수 있어야 한다 — 과거의 동의는 대개 과거의 문안을 가리킨다.
     */
    Optional<PrivacyConsentTerms> findByCodeAndVersion(String code, int version);
}
