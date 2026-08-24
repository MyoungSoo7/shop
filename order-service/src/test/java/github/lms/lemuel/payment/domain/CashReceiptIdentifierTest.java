package github.lms.lemuel.payment.domain;

import github.lms.lemuel.payment.domain.exception.CashReceiptNotAllowedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 현금영수증 식별번호 VO.
 *
 * <p>여기서 막지 못한 오타는 국세청까지 갔다가 며칠 뒤 반려로 돌아온다 — 사업자등록번호는 그 사이
 * 남의 번호로 지출증빙이 발급될 수도 있고, 발급 취소는 사후 정정 신고 대상이라 되돌리는 비용이 크다.
 */
@DisplayName("CashReceiptIdentifier — 형식 검증 · 정규화 · 마스킹")
class CashReceiptIdentifierTest {

    @Nested
    @DisplayName("사업자등록번호 체크섬")
    class BusinessNumber {

        @ParameterizedTest(name = "유효: {0}")
        @ValueSource(strings = {"220-81-62517", "124-81-00998", "2208162517"})
        @DisplayName("실재하는 형식의 번호는 통과하고 숫자만 남긴다")
        void acceptsValid(String raw) {
            CashReceiptIdentifier id =
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.BUSINESS_NUMBER, raw);

            assertThat(id.getValue()).matches("\\d{10}");
        }

        @Test
        @DisplayName("한 자리만 틀려도 체크섬이 잡는다 — 길이 검사만으로는 통과했을 오타")
        void rejectsSingleDigitTypo() {
            assertThatThrownBy(() ->
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.BUSINESS_NUMBER, "2208162518"))
                    .isInstanceOf(CashReceiptNotAllowedException.class)
                    .hasMessageContaining("체크섬");
        }

        @Test
        @DisplayName("10 자리가 아니면 거부")
        void rejectsWrongLength() {
            assertThatThrownBy(() ->
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.BUSINESS_NUMBER, "220816251"))
                    .isInstanceOf(CashReceiptNotAllowedException.class)
                    .hasMessageContaining("10");
        }
    }

    @Nested
    @DisplayName("휴대폰번호")
    class Mobile {

        @ParameterizedTest(name = "유효: {0}")
        @ValueSource(strings = {"010-1234-5678", "01012345678", "011-234-5678"})
        void acceptsValid(String raw) {
            assertThat(CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, raw).getValue())
                    .doesNotContain("-");
        }

        @ParameterizedTest(name = "거부: {0}")
        @ValueSource(strings = {"02-123-4567", "0101234", "015-1234-5678"})
        void rejectsInvalid(String raw) {
            assertThatThrownBy(() -> CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, raw))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
        }

        @Test
        @DisplayName("하이픈 유무가 달라도 같은 값이다 — 갈라지면 중복 발급 판정이 무너진다")
        void normalizationMakesThemEqual() {
            CashReceiptIdentifier hyphen =
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "010-1234-5678");
            CashReceiptIdentifier plain =
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "01012345678");

            assertThat(hyphen).isEqualTo(plain);
            assertThat(hyphen.hashCode()).isEqualTo(plain.hashCode());
        }
    }

    @Nested
    @DisplayName("현금영수증카드")
    class CashReceiptCard {

        @Test
        void accepts13To19Digits() {
            assertThat(CashReceiptIdentifier.of(
                    CashReceiptIdentifier.Type.CASH_RECEIPT_CARD, "1234567890123").getValue())
                    .hasSize(13);
        }

        @Test
        void rejectsTooShort() {
            assertThatThrownBy(() -> CashReceiptIdentifier.of(
                    CashReceiptIdentifier.Type.CASH_RECEIPT_CARD, "123456789012"))
                    .isInstanceOf(CashReceiptNotAllowedException.class);
        }
    }

    @Nested
    @DisplayName("마스킹")
    class Masking {

        @Test
        @DisplayName("뒤 4 자리만 남긴다 — 본인 확인에는 충분하고, 새어도 재구성되지 않는다")
        void keepsLastFour() {
            CashReceiptIdentifier id =
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "010-1234-5678");

            assertThat(id.masked()).isEqualTo("*******5678");
        }

        @Test
        @DisplayName("toString 도 마스킹된다 — 로그에 실려도 원문이 새지 않게")
        void toStringIsMasked() {
            CashReceiptIdentifier id =
                    CashReceiptIdentifier.of(CashReceiptIdentifier.Type.BUSINESS_NUMBER, "2208162517");

            assertThat(id.toString()).doesNotContain("2208162517").contains("2517");
        }
    }

    @Test
    @DisplayName("빈 값·null 은 종류와 무관하게 거부")
    void rejectsEmpty() {
        assertThatThrownBy(() -> CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "  "))
                .isInstanceOf(CashReceiptNotAllowedException.class);
        assertThatThrownBy(() -> CashReceiptIdentifier.of(null, "01012345678"))
                .isInstanceOf(CashReceiptNotAllowedException.class);
    }
}
