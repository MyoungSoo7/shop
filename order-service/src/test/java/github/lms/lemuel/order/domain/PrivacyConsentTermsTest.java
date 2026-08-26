package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동의 문안 — "무엇을 고지하는가".
 *
 * <p>여기서 지키는 것은 두 가지다. 하나는 <b>고지가 빠진 문안은 만들어지지 않는다</b>는 것이고
 * (특히 제3자 제공에서 제공받는 자), 다른 하나는 <b>동의를 기록으로 만들 때 고지 내용을 값으로
 * 복사해 간다</b>는 것이다. 뒤엣것이 없으면 문안을 고친 순간 과거의 동의가 무엇에 대한
 * 동의였는지 되찾을 방법이 없다.
 */
class PrivacyConsentTermsTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

    private static PrivacyConsentTerms terms(ConsentType type, String recipient, boolean required,
                                             LocalDateTime effectiveTo) {
        return PrivacyConsentTerms.restore(1L, "THIRD_PARTY_DELIVERY", 2, type,
                "배송을 위한 개인정보 제3자 제공 동의", recipient,
                "주문 상품의 배송", "받는 분 이름, 휴대전화번호, 주소", "배송 완료 후 90일",
                "전문입니다", "sha-256-of-body", required, FROM, effectiveTo, FROM);
    }

    private static PrivacyConsentTerms required() {
        return terms(ConsentType.THIRD_PARTY_PROVISION, "배송업체", true, null);
    }

    @Nested
    @DisplayName("만들어질 때")
    class Creation {

        @Test @DisplayName("제3자 제공인데 제공받는 자가 없으면 만들어지지 않는다")
        void thirdPartyRequiresRecipient() {
            assertThatThrownBy(() -> terms(ConsentType.THIRD_PARTY_PROVISION, null, true, null))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("제공받는 자");

            assertThatThrownBy(() -> terms(ConsentType.THIRD_PARTY_PROVISION, "   ", true, null))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test @DisplayName("제3자 제공이 아니면 제공받는 자는 비어도 된다")
        void otherTypesMayOmitRecipient() {
            assertThatCode(() -> terms(ConsentType.COLLECTION_USE, null, true, null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> terms(ConsentType.MARKETING, null, false, null))
                    .doesNotThrowAnyException();
        }

        @Test @DisplayName("버전은 양수여야 한다")
        void versionMustBePositive() {
            assertThatThrownBy(() -> PrivacyConsentTerms.restore(1L, "CODE", 0, ConsentType.COLLECTION_USE,
                    "제목", null, "목적", "항목", "보유", "전문", "hash", true, FROM, null, FROM))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("버전");
        }

        @Test @DisplayName("고지 항목이 비면 만들어지지 않는다 — 빈 문안으로 받은 동의는 동의가 아니다")
        void mandatoryTextsCannotBeBlank() {
            assertThatThrownBy(() -> PrivacyConsentTerms.restore(1L, "CODE", 1, ConsentType.COLLECTION_USE,
                    "제목", null, "  ", "항목", "보유", "전문", "hash", true, FROM, null, FROM))
                    .isInstanceOf(OrderInvariantViolationException.class);

            assertThatThrownBy(() -> PrivacyConsentTerms.restore(1L, "CODE", 1, ConsentType.COLLECTION_USE,
                    "제목", null, "목적", "항목", "보유", "", "hash", true, FROM, null, FROM))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test @DisplayName("유효기간이 거꾸로면 만들어지지 않는다")
        void effectiveRangeMustMoveForward() {
            assertThatThrownBy(() -> terms(ConsentType.COLLECTION_USE, null, true, FROM.minusDays(1)))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("유효기간");
        }
    }

    @Nested
    @DisplayName("유효기간")
    class Effectiveness {

        @Test @DisplayName("종료가 비면 시작 이후로 계속 현행이다")
        void openEnded() {
            PrivacyConsentTerms current = terms(ConsentType.COLLECTION_USE, null, true, null);

            assertThat(current.isEffectiveAt(FROM)).isTrue();
            assertThat(current.isEffectiveAt(NOW)).isTrue();
            assertThat(current.isEffectiveAt(FROM.minusSeconds(1))).isFalse();
        }

        @Test @DisplayName("경계는 시작 포함·종료 제외다")
        void boundaries() {
            LocalDateTime to = FROM.plusDays(10);
            PrivacyConsentTerms retired = terms(ConsentType.COLLECTION_USE, null, true, to);

            assertThat(retired.isEffectiveAt(FROM)).isTrue();
            assertThat(retired.isEffectiveAt(to.minusSeconds(1))).isTrue();
            // 종료 시각 그 자체는 이미 현행이 아니다. 포함으로 두면 새 버전과 겹치는 순간이 생긴다.
            assertThat(retired.isEffectiveAt(to)).isFalse();
        }
    }

    @Nested
    @DisplayName("동의를 기록으로 만들 때")
    class Accepting {

        @Test @DisplayName("고지 4종을 값으로 복사해 간다 — 참조만 하면 문안이 바뀔 때 과거를 잃는다")
        void copiesDisclosures() {
            PrivacyConsentTerms terms = required();

            OrderPrivacyConsent consent = terms.accept(7L, 42L, true, NOW, "203.0.113.7");

            assertThat(consent.getOrderId()).isEqualTo(7L);
            assertThat(consent.getUserId()).isEqualTo(42L);
            assertThat(consent.getTermsCode()).isEqualTo("THIRD_PARTY_DELIVERY");
            assertThat(consent.getTermsVersion()).isEqualTo(2);
            assertThat(consent.getConsentType()).isEqualTo(ConsentType.THIRD_PARTY_PROVISION);
            assertThat(consent.isAgreed()).isTrue();
            assertThat(consent.getRecipient()).isEqualTo("배송업체");
            assertThat(consent.getPurpose()).isEqualTo("주문 상품의 배송");
            assertThat(consent.getProvidedItems()).isEqualTo("받는 분 이름, 휴대전화번호, 주소");
            assertThat(consent.getRetention()).isEqualTo("배송 완료 후 90일");
            assertThat(consent.getBodySha256()).isEqualTo("sha-256-of-body");
            assertThat(consent.getAgreedAt()).isEqualTo(NOW);
            assertThat(consent.getIpAddress()).isEqualTo("203.0.113.7");
        }

        @Test @DisplayName("선택 항목은 거절도 기록으로 남는다 — \"물었고 거절했다\"와 \"묻지 않았다\"는 다르다")
        void optionalRefusalIsRecorded() {
            PrivacyConsentTerms optional = terms(ConsentType.MARKETING, null, false, null);

            OrderPrivacyConsent consent = optional.accept(7L, 42L, false, NOW, null);

            assertThat(consent.isAgreed()).isFalse();
            assertThat(consent.getIpAddress()).isNull();
        }

        @Test @DisplayName("필수 항목을 거절한 채로는 기록조차 만들 수 없다 — 그런 주문이 성립하지 않는다")
        void requiredRefusalCannotBeRecorded() {
            assertThatThrownBy(() -> required().accept(7L, 42L, false, NOW, null))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("필수 동의");
        }
    }
}
