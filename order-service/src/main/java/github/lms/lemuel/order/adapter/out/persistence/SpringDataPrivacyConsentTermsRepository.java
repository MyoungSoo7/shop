package github.lms.lemuel.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SpringDataPrivacyConsentTermsRepository
        extends JpaRepository<PrivacyConsentTermsJpaEntity, Long> {

    /**
     * 주어진 시각에 유효한 문안.
     *
     * <p>경계를 시작 포함·종료 제외로 잡는 것이 도메인의
     * {@code PrivacyConsentTerms#isEffectiveAt} 와 같다. 두 자리가 어긋나면 교체 순간에 문안이
     * 둘 다 보이거나 둘 다 안 보인다.
     */
    @Query("""
            SELECT t FROM PrivacyConsentTermsJpaEntity t
            WHERE t.effectiveFrom <= :at AND (t.effectiveTo IS NULL OR t.effectiveTo > :at)
            ORDER BY t.code ASC
            """)
    List<PrivacyConsentTermsJpaEntity> findEffectiveAt(@Param("at") LocalDateTime at);

    Optional<PrivacyConsentTermsJpaEntity> findByCodeAndVersion(String code, int version);
}
