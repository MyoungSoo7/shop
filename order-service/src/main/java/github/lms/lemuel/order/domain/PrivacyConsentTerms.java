package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 동의 문안 한 벌 — "무엇을 고지하는가".
 *
 * <p><b>고칠 수 없다.</b> 문장을 바꿔야 하면 이 행을 수정하는 것이 아니라 버전을 올려 새 행을
 * 만든다. 이미 이 문안으로 동의한 사람이 있는데 문장을 바꾸면, 그 사람의 동의가 본 적 없는
 * 내용에 대한 동의로 조용히 바뀌기 때문이다. 그래서 이 클래스에는 setter 도, 상태를 바꾸는
 * 메서드도 없다.
 *
 * <p>유효기간은 {@code effectiveFrom} ~ {@code effectiveTo} 이고 {@code effectiveTo} 가 비면
 * 현행이다. 결제 화면은 "지금 유효한 문안"만 보여 주지만, 지난 문안도 지우지 않는다 — 과거의
 * 동의 이력이 가리키는 대상이 사라지면 그 이력을 읽을 수 없다.
 */
public final class PrivacyConsentTerms {

    private final Long id;
    private final String code;
    private final int version;
    private final ConsentType consentType;
    private final String title;
    private final String recipient;
    private final String purpose;
    private final String providedItems;
    private final String retention;
    private final String body;
    private final String bodySha256;
    private final boolean required;
    private final LocalDateTime effectiveFrom;
    private final LocalDateTime effectiveTo;
    private final LocalDateTime createdAt;

    private PrivacyConsentTerms(Long id, String code, int version, ConsentType consentType, String title,
                                String recipient, String purpose, String providedItems, String retention,
                                String body, String bodySha256, boolean required,
                                LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
                                LocalDateTime createdAt) {
        this.id = id;
        this.code = requireText(code, "문안 코드");
        this.version = version;
        this.consentType = Objects.requireNonNull(consentType, "consentType");
        this.title = requireText(title, "제목");
        this.recipient = recipient;
        this.purpose = requireText(purpose, "이용·제공 목적");
        this.providedItems = requireText(providedItems, "수집·제공 항목");
        this.retention = requireText(retention, "보유·이용 기간");
        this.body = requireText(body, "전문");
        this.bodySha256 = requireText(bodySha256, "전문 해시");
        this.required = required;
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        this.effectiveTo = effectiveTo;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");

        if (version <= 0) {
            throw new OrderInvariantViolationException("문안 버전은 양수여야 합니다");
        }
        // 넘기는 상대를 안 적은 제3자 제공 문안은, 법이 요구하는 고지를 못 한 문안이다.
        if (consentType.requiresRecipient() && (recipient == null || recipient.isBlank())) {
            throw new OrderInvariantViolationException(
                    "제3자 제공 문안에는 제공받는 자가 있어야 합니다: " + code);
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new OrderInvariantViolationException("문안 유효기간이 거꾸로입니다: " + code);
        }
    }

    public static PrivacyConsentTerms restore(Long id, String code, int version, ConsentType consentType,
                                              String title, String recipient, String purpose,
                                              String providedItems, String retention,
                                              String body, String bodySha256, boolean required,
                                              LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
                                              LocalDateTime createdAt) {
        return new PrivacyConsentTerms(id, code, version, consentType, title, recipient, purpose,
                providedItems, retention, body, bodySha256, required, effectiveFrom, effectiveTo, createdAt);
    }

    /** 주어진 시각에 이 문안이 유효한가. 경계는 시작 포함·종료 제외다. */
    public boolean isEffectiveAt(LocalDateTime at) {
        if (at.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || at.isBefore(effectiveTo);
    }

    /**
     * 이 문안에 대한 동의(또는 거부)를 기록으로 만든다.
     *
     * <p>고지 4종을 그대로 복사해 넘기는 것이 핵심이다. 기록이 문안 행을 참조만 하면, 나중에
     * 문안이 바뀌었을 때 과거의 동의가 무엇에 대한 동의였는지 되찾을 방법이 없다.
     */
    public OrderPrivacyConsent accept(Long orderId, Long userId, boolean agreed,
                                      LocalDateTime agreedAt, String ipAddress) {
        if (required && !agreed) {
            // 필수 동의를 거부한 채로 기록만 남기는 경로는 없다. 그런 주문은 성립하지 않으므로
            // 남길 주문 번호 자체가 없다.
            throw new OrderInvariantViolationException("필수 동의는 거부 상태로 기록할 수 없습니다: " + code);
        }
        return OrderPrivacyConsent.record(orderId, userId, code, version, consentType, agreed,
                recipient, purpose, providedItems, retention, bodySha256, agreedAt, ipAddress);
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new OrderInvariantViolationException(what + "이(가) 비어 있습니다");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getVersion() {
        return version;
    }

    public ConsentType getConsentType() {
        return consentType;
    }

    public String getTitle() {
        return title;
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

    public String getBody() {
        return body;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public boolean isRequired() {
        return required;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
