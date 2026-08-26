package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.application.port.out.LoadOrderPrivacyConsentPort;
import github.lms.lemuel.order.application.port.out.LoadPrivacyConsentTermsPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPrivacyConsentPort;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class PrivacyConsentPersistenceAdapter
        implements LoadPrivacyConsentTermsPort, SaveOrderPrivacyConsentPort, LoadOrderPrivacyConsentPort {

    /** 목록 조회의 상한 — 호출자가 더 큰 값을 줘도 여기서 잘린다. */
    private static final int MAX_LIMIT = 500;

    private final SpringDataPrivacyConsentTermsRepository termsRepository;
    private final SpringDataOrderPrivacyConsentRepository consentRepository;

    public PrivacyConsentPersistenceAdapter(SpringDataPrivacyConsentTermsRepository termsRepository,
                                            SpringDataOrderPrivacyConsentRepository consentRepository) {
        this.termsRepository = termsRepository;
        this.consentRepository = consentRepository;
    }

    @Override
    public List<PrivacyConsentTerms> findEffectiveAt(LocalDateTime at) {
        if (at == null) {
            return List.of();
        }
        return termsRepository.findEffectiveAt(at).stream()
                .map(PrivacyConsentTermsJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<PrivacyConsentTerms> findByCodeAndVersion(String code, int version) {
        if (code == null || code.isBlank() || version <= 0) {
            return Optional.empty();
        }
        return termsRepository.findByCodeAndVersion(code, version)
                .map(PrivacyConsentTermsJpaEntity::toDomain);
    }

    @Override
    public List<OrderPrivacyConsent> saveAll(List<OrderPrivacyConsent> consents) {
        if (consents == null || consents.isEmpty()) {
            return List.of();
        }
        // 이 표는 갱신하지 않으므로 로드-후-수정이 없다. 새 행만 들어온다.
        List<OrderPrivacyConsentJpaEntity> entities = consents.stream()
                .map(OrderPrivacyConsentJpaEntity::fromDomain)
                .toList();
        return consentRepository.saveAll(entities).stream()
                .map(OrderPrivacyConsentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<OrderPrivacyConsent> findByOrderId(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        return consentRepository.findByOrderIdOrderByTermsCodeAsc(orderId).stream()
                .map(OrderPrivacyConsentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<OrderPrivacyConsent> findByUserId(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return consentRepository
                .findByUserIdOrderByAgreedAtDesc(userId, PageRequest.of(0, capped(limit))).stream()
                .map(OrderPrivacyConsentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<OrderPrivacyConsent> findByTermsCodeAndVersion(String termsCode, int termsVersion, int limit) {
        if (termsCode == null || termsCode.isBlank() || termsVersion <= 0) {
            return List.of();
        }
        return consentRepository
                .findByTermsCodeAndTermsVersionOrderByAgreedAtDesc(
                        termsCode, termsVersion, PageRequest.of(0, capped(limit))).stream()
                .map(OrderPrivacyConsentJpaEntity::toDomain)
                .toList();
    }

    private static int capped(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }
}
