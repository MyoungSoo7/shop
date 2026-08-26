package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

/**
 * 동의의 종류.
 *
 * <p>종류를 나누는 이유는 화면 정렬용 꼬리표가 아니라, 종류마다 <b>고지해야 하는 것이 다르기</b>
 * 때문이다. 개인정보 보호법 제17조가 요구하는 "제공받는 자"는 제3자 제공에만 있는 항목이고,
 * 수집·이용 동의에는 애초에 존재하지 않는다. {@link #requiresRecipient()} 가 그 차이를 들고 있고,
 * 같은 규칙이 DB 의 {@code ck_privacy_consent_terms_recipient} 로 한 번 더 서 있다.
 */
public enum ConsentType {

    /** 우리가 직접 수집해 쓰는 것 — 주문 접수·결제·배송·민원. */
    COLLECTION_USE,

    /** 남에게 넘기는 것 — 배송업체 등. 넘기는 상대를 반드시 밝혀야 한다. */
    THIRD_PARTY_PROVISION,

    /** 결제대행사 이용에 따른 동의. */
    PAYMENT_AGENCY,

    /** 광고성 정보 수신. 선택 동의라 거부해도 주문은 성립한다. */
    MARKETING;

    /** 이 종류의 문안은 "제공받는 자"를 비워 둘 수 없다. */
    public boolean requiresRecipient() {
        return this == THIRD_PARTY_PROVISION;
    }

    public static ConsentType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new OrderInvariantViolationException("동의 종류가 비어 있습니다");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new OrderInvariantViolationException("알 수 없는 동의 종류: " + value);
        }
    }
}
