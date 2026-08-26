package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동의 이력 한 줄 — "누가 언제 무엇에 동의했는가".
 *
 * <p>여기서 검사하는 핵심은 {@link OrderPrivacyConsent#matchesBodyOf} 다. 이 판정이 느슨하면
 * "동의 이후 문안이 손질됐다"는 사실이 화면에서 사라진다 — 그 사실을 감추는 것이 이 기능의
 * 반대 방향이다.
 */
class OrderPrivacyConsentTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime AGREED_AT = LocalDateTime.of(2026, 8, 27, 10, 0);

    private static PrivacyConsentTerms terms(String code, int version, String bodySha256) {
        return PrivacyConsentTerms.restore(1L, code, version, ConsentType.COLLECTION_USE,
                "제목", null, "목적", "항목", "보유", "전문", bodySha256, true, FROM, null, FROM);
    }

    private static OrderPrivacyConsent consentWith(String ip) {
        return terms("COLLECTION_USE_ORDER", 1, "hash-v1").accept(7L, 42L, true, AGREED_AT, ip);
    }

    @Nested
    @DisplayName("문안 대조")
    class BodyMatching {

        @Test @DisplayName("코드·버전·본문 해시가 모두 같아야 \"그대로\"다")
        void matchesOnlyWhenAllThreeAgree() {
            OrderPrivacyConsent consent = consentWith(null);

            assertThat(consent.matchesBodyOf(terms("COLLECTION_USE_ORDER", 1, "hash-v1"))).isTrue();
            // 버전을 올리지 않고 문장만 고친 경우 — 이 판정이 잡아야 하는 바로 그 형태다.
            assertThat(consent.matchesBodyOf(terms("COLLECTION_USE_ORDER", 1, "hash-tampered"))).isFalse();
            assertThat(consent.matchesBodyOf(terms("COLLECTION_USE_ORDER", 2, "hash-v1"))).isFalse();
            assertThat(consent.matchesBodyOf(terms("OTHER_CODE", 1, "hash-v1"))).isFalse();
        }

        @Test @DisplayName("대조할 문안이 없으면 \"같다\"고 말하지 않는다")
        void nullTermsIsNotAMatch() {
            // 확인되지 않은 것을 확인된 것으로 보이게 하면 이 칸의 의미가 없어진다.
            assertThat(consentWith(null).matchesBodyOf(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("접속지(IP)")
    class IpNormalization {

        @Test @DisplayName("프록시 헤더로 여러 개가 오면 첫 값만 남긴다")
        void keepsFirstHop() {
            // X-Forwarded-For 는 "client, proxy1, proxy2" 로 온다. 통째로 넣으면 컬럼을 넘치거나
            // 사람이 읽을 수 없는 값이 된다.
            assertThat(consentWith("203.0.113.7, 10.0.0.1, 10.0.0.2").getIpAddress())
                    .isEqualTo("203.0.113.7");
        }

        @Test @DisplayName("비었으면 null 로 둔다 — 빈 문자열은 \"관찰했다\"처럼 보인다")
        void blankBecomesNull() {
            assertThat(consentWith("").getIpAddress()).isNull();
            assertThat(consentWith("   ").getIpAddress()).isNull();
            assertThat(consentWith(null).getIpAddress()).isNull();
        }

        @Test @DisplayName("컬럼 길이(45)를 넘으면 자른다")
        void truncatesToColumnWidth() {
            String tooLong = "2001:0db8:0000:0000:0000:ff00:0042:8329:extra-tail-that-overflows";

            assertThat(consentWith(tooLong).getIpAddress()).hasSize(45);
        }
    }

    @Nested
    @DisplayName("불변식")
    class Invariants {

        @Test @DisplayName("버전은 양수여야 한다")
        void versionMustBePositive() {
            assertThatThrownBy(() -> OrderPrivacyConsent.restore(1L, 7L, 42L, "CODE", 0,
                    ConsentType.COLLECTION_USE, true, null, "목적", "항목", "보유", "hash",
                    AGREED_AT, null, AGREED_AT))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test @DisplayName("주문·동의자·고지 항목이 없으면 만들어지지 않는다")
        void requiredFieldsAreNotNull() {
            assertThatThrownBy(() -> OrderPrivacyConsent.restore(1L, null, 42L, "CODE", 1,
                    ConsentType.COLLECTION_USE, true, null, "목적", "항목", "보유", "hash",
                    AGREED_AT, null, AGREED_AT))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> OrderPrivacyConsent.restore(1L, 7L, 42L, "CODE", 1,
                    ConsentType.COLLECTION_USE, true, null, "목적", "항목", "보유", null,
                    AGREED_AT, null, AGREED_AT))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test @DisplayName("새로 기록하면 동의 시각이 곧 생성 시각이다")
        void agreedAtIsAlsoCreatedAt() {
            // 서버가 찍은 한 시각이다. 둘이 갈라지면 어느 쪽이 "동의한 때"인지 알 수 없게 된다.
            OrderPrivacyConsent consent = consentWith(null);

            assertThat(consent.getId()).isNull();
            assertThat(consent.getCreatedAt()).isEqualTo(consent.getAgreedAt());
        }
    }
}
