package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 주문 시점 동의 이력 한 줄 — "누가 언제 무엇에 동의했는가".
 *
 * <p>{@link PrivacyConsentTerms} 와 마찬가지로 만들어진 뒤에는 바뀌지 않는다. 동의를 나중에
 * 고칠 수 있으면 이력이 아니라 그냥 현재 상태다. 철회는 이 행을 뒤집는 것이 아니라 별도의
 * 사건으로 남아야 한다.
 *
 * <p>문안 표를 참조하지 않고 고지 4종을 값으로 들고 있는 이유는 저장 스키마 주석에 적어 두었다 —
 * 요약하면, 문안이 바뀌어도 이 행이 혼자 서서 "그때 화면에 무엇이 적혀 있었는지"를 말할 수 있어야
 * 하기 때문이다.
 */
public final class OrderPrivacyConsent {

    private final Long id;
    private final Long orderId;
    private final Long userId;
    private final String termsCode;
    private final int termsVersion;
    private final ConsentType consentType;
    private final boolean agreed;
    private final String recipient;
    private final String purpose;
    private final String providedItems;
    private final String retention;
    private final String bodySha256;
    private final LocalDateTime agreedAt;
    private final String ipAddress;
    private final LocalDateTime createdAt;

    private OrderPrivacyConsent(Long id, Long orderId, Long userId, String termsCode, int termsVersion,
                                ConsentType consentType, boolean agreed, String recipient, String purpose,
                                String providedItems, String retention, String bodySha256,
                                LocalDateTime agreedAt, String ipAddress, LocalDateTime createdAt) {
        this.id = id;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.termsCode = Objects.requireNonNull(termsCode, "termsCode");
        this.termsVersion = termsVersion;
        this.consentType = Objects.requireNonNull(consentType, "consentType");
        this.agreed = agreed;
        this.recipient = recipient;
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.providedItems = Objects.requireNonNull(providedItems, "providedItems");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.bodySha256 = Objects.requireNonNull(bodySha256, "bodySha256");
        this.agreedAt = Objects.requireNonNull(agreedAt, "agreedAt");
        this.ipAddress = ipAddress;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (termsVersion <= 0) {
            throw new OrderInvariantViolationException("문안 버전은 양수여야 합니다");
        }
    }

    static OrderPrivacyConsent record(Long orderId, Long userId, String termsCode, int termsVersion,
                                      ConsentType consentType, boolean agreed, String recipient,
                                      String purpose, String providedItems, String retention,
                                      String bodySha256, LocalDateTime agreedAt, String ipAddress) {
        return new OrderPrivacyConsent(null, orderId, userId, termsCode, termsVersion, consentType, agreed,
                recipient, purpose, providedItems, retention, bodySha256, agreedAt,
                normalizeIp(ipAddress), agreedAt);
    }

    public static OrderPrivacyConsent restore(Long id, Long orderId, Long userId, String termsCode,
                                              int termsVersion, ConsentType consentType, boolean agreed,
                                              String recipient, String purpose, String providedItems,
                                              String retention, String bodySha256, LocalDateTime agreedAt,
                                              String ipAddress, LocalDateTime createdAt) {
        return new OrderPrivacyConsent(id, orderId, userId, termsCode, termsVersion, consentType, agreed,
                recipient, purpose, providedItems, retention, bodySha256, agreedAt, ipAddress, createdAt);
    }

    /**
     * 지금 카탈로그에 있는 같은 (코드, 버전) 문안과 견줘 본문이 그대로인지 본다.
     *
     * <p>{@code false} 가 나오면 이 동의 이후에 문안이 손질됐다는 뜻이다. 그 자체로 잘못은
     * 아니지만(버전을 올렸어야 한다는 신호다), 이 동의를 근거로 내밀 때는 반드시 알아야 한다.
     */
    public boolean matchesBodyOf(PrivacyConsentTerms terms) {
        return terms != null
                && termsCode.equals(terms.getCode())
                && termsVersion == terms.getVersion()
                && bodySha256.equals(terms.getBodySha256());
    }

    /** IP 는 프록시 헤더에서 올 수 있어 목록으로 도착한다. 첫 값만 남기고 길이를 컬럼에 맞춘다. */
    private static String normalizeIp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String first = raw.split(",")[0].trim();
        return first.length() > 45 ? first.substring(0, 45) : first;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTermsCode() {
        return termsCode;
    }

    public int getTermsVersion() {
        return termsVersion;
    }

    public ConsentType getConsentType() {
        return consentType;
    }

    public boolean isAgreed() {
        return agreed;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getProvidedItems() {
        return providedItems;
    }

    public String getRetention() {
        return retention;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public LocalDateTime getAgreedAt() {
        return agreedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
