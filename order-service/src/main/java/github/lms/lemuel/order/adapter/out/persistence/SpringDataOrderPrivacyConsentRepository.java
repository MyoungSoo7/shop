package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataOrderPrivacyConsentRepository
        extends JpaRepository<OrderPrivacyConsentJpaEntity, Long> {

    List<OrderPrivacyConsentJpaEntity> findByOrderIdOrderByTermsCodeAsc(Long orderId);

    List<OrderPrivacyConsentJpaEntity> findByUserIdOrderByAgreedAtDesc(Long userId, Pageable pageable);

    List<OrderPrivacyConsentJpaEntity> findByTermsCodeAndTermsVersionOrderByAgreedAtDesc(
            String termsCode, int termsVersion, Pageable pageable);
}
