package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.ConsentType;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * {@code order_privacy_consents} 매핑 — <b>넣기만 하고 고치지 않는다</b>.
 *
 * <p>{@code applyFrom} 같은 갱신 경로를 두지 않은 것이 의도다. 동의를 나중에 고칠 수 있으면
 * 이력이 아니라 현재 상태이고, 현재 상태는 "그때 동의했다"를 증명하지 못한다. 철회는 이 행을
 * 뒤집는 것이 아니라 별도의 사건으로 남아야 한다.
 *
 * <p>문안 표를 참조하지 않고 고지 4종을 값으로 들고 있는 이유는 마이그레이션 주석에 적어 두었다.
 * 외래키가 없는 것도 같은 이유다 — 문안 행이 사라져도 이 행은 혼자 서야 한다.
 */
@Entity
@Table(name = "order_privacy_consents")
public class OrderPrivacyConsentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "terms_code", nullable = false, length = 60)
    private String termsCode;

    @Column(name = "terms_version", nullable = false)
    private int termsVersion;

    @Column(name = "consent_type", nullable = false, length = 30)
    private String consentType;

    @Column(nullable = false)
    private boolean agreed;

    @Column(length = 200)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String purpose;

    @Column(name = "provided_items", nullable = false, length = 500)
    private String providedItems;

    @Column(nullable = false, length = 200)
    private String retention;

    @Column(name = "body_sha256", nullable = false, length = 64)
    private String bodySha256;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected OrderPrivacyConsentJpaEntity() {
    }

    static OrderPrivacyConsentJpaEntity fromDomain(OrderPrivacyConsent consent) {
        OrderPrivacyConsentJpaEntity entity = new OrderPrivacyConsentJpaEntity();
        entity.id = consent.getId();
        entity.orderId = consent.getOrderId();
        entity.userId = consent.getUserId();
        entity.termsCode = consent.getTermsCode();
        entity.termsVersion = consent.getTermsVersion();
        entity.consentType = consent.getConsentType().name();
        entity.agreed = consent.isAgreed();
        entity.recipient = consent.getRecipient();
        entity.purpose = consent.getPurpose();
        entity.providedItems = consent.getProvidedItems();
        entity.retention = consent.getRetention();
        entity.bodySha256 = consent.getBodySha256();
        entity.agreedAt = consent.getAgreedAt();
        entity.ipAddress = consent.getIpAddress();
        entity.createdAt = consent.getCreatedAt();
        return entity;
    }

    OrderPrivacyConsent toDomain() {
        return OrderPrivacyConsent.restore(id, orderId, userId, termsCode, termsVersion,
                ConsentType.fromString(consentType), agreed, recipient, purpose, providedItems,
                retention, bodySha256, agreedAt, ipAddress, createdAt);
    }

    public Long getId() {
        return id;
    }
}
