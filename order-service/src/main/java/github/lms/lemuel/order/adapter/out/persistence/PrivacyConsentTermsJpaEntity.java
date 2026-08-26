package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.ConsentType;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code privacy_consent_terms} 매핑 — <b>읽기 전용</b>이다.
 *
 * <p>도메인에서 오는 쓰기 경로({@code fromDomain}/{@code applyFrom})를 두지 않았다. 문안은 마이그
 * 레이션이나 운영 도구로만 들어오고, 애플리케이션 코드가 고칠 수 있으면 "고치지 않는다"는 규칙이
 * 규칙이 아니라 관습이 된다. 버전을 올리지 않은 수정 한 번이 과거의 동의 전부를 다른 내용에 대한
 * 동의로 바꾼다.
 *
 * <p>enum 은 {@code String} 으로 담는다 — 순서 기반 저장은 상수를 끼워 넣는 순간 저장된 모든 행의
 * 뜻이 조용히 바뀐다.
 */
@Entity
@Table(name = "privacy_consent_terms")
public class PrivacyConsentTermsJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(name = "consent_type", nullable = false, length = 30)
    private String consentType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 200)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String purpose;

    @Column(name = "provided_items", nullable = false, length = 500)
    private String providedItems;

    @Column(nullable = false, length = 200)
    private String retention;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "body_sha256", nullable = false, length = 64)
    private String bodySha256;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PrivacyConsentTermsJpaEntity() {
    }

    PrivacyConsentTerms toDomain() {
        return PrivacyConsentTerms.restore(id, code, version, ConsentType.fromString(consentType), title,
                recipient, purpose, providedItems, retention, body, bodySha256, required,
                effectiveFrom, effectiveTo, createdAt);
    }

    public Long getId() {
        return id;
    }
}
