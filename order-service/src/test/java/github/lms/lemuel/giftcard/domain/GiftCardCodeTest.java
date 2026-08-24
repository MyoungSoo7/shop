package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기프트카드 코드 생성·정규화·해시 단위 테스트.
 *
 * <p>코드는 그 자체가 재산이다. 평문은 저장되지 않고 발행 응답에서 한 번만 나가므로,
 * 여기서 고정하는 것은 <b>엔트로피</b>와 <b>같은 코드가 언제나 같은 해시로 접힌다</b>는 두 가지다.
 */
class GiftCardCodeTest {

    @Test
    @DisplayName("생성 코드는 GC- 접두 + 16자 본문 형식이다")
    void generatedFormat() {
        String code = GiftCardCode.generate();

        assertThat(code).matches("^GC-[0-9A-HJKMNP-TV-Z]{16}$");
    }

    @Test
    @DisplayName("연속 생성해도 겹치지 않는다 — 무차별 대입이 성립하지 않을 만큼의 엔트로피")
    void generatedCodesAreUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            codes.add(GiftCardCode.generate());
        }

        assertThat(codes).hasSize(500);
    }

    @Test
    @DisplayName("혼동하기 쉬운 글자(I·L·O·U)를 쓰지 않는다 — 사람이 받아 적는 코드다")
    void avoidsAmbiguousLetters() {
        for (int i = 0; i < 200; i++) {
            assertThat(GiftCardCode.generate().substring(3)).doesNotContainAnyWhitespaces()
                    .doesNotContain("I").doesNotContain("L").doesNotContain("O").doesNotContain("U");
        }
    }

    @Test
    @DisplayName("하이픈·공백·대소문자 차이는 같은 코드로 접힌다 — 사용자가 어떻게 입력하든 동작한다")
    void normalizationFoldsInputVariants() {
        String canonical = GiftCardCode.hashOf("GC-ABCD2345EFGH6789");

        assertThat(GiftCardCode.hashOf("gc-abcd2345efgh6789")).isEqualTo(canonical);
        assertThat(GiftCardCode.hashOf("GC ABCD 2345 EFGH 6789")).isEqualTo(canonical);
        assertThat(GiftCardCode.hashOf("  GC-ABCD-2345-EFGH-6789  ")).isEqualTo(canonical);
    }

    @Test
    @DisplayName("해시는 64자 16진수다 — SHA-256")
    void hashIsSha256Hex() {
        assertThat(GiftCardCode.hashOf(GiftCardCode.generate())).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("다른 코드는 다른 해시가 된다")
    void differentCodesDifferentHashes() {
        assertThat(GiftCardCode.hashOf("GC-ABCD2345EFGH6789"))
                .isNotEqualTo(GiftCardCode.hashOf("GC-ABCD2345EFGH678A"));
    }

    @Test
    @DisplayName("표시는 마지막 4자리만 — 전체 코드는 어디에도 다시 나타나지 않는다")
    void last4IsDisplayForm() {
        assertThat(GiftCardCode.last4("GC-ABCD2345EFGH6789")).isEqualTo("6789");
        assertThat(GiftCardCode.last4("gc-abcd-2345-efgh-6789")).isEqualTo("6789");
    }

    @Test
    @DisplayName("빈 코드는 거절한다")
    void rejectsBlankCode() {
        assertThatThrownBy(() -> GiftCardCode.hashOf("  "))
                .isInstanceOf(InvalidGiftCardStateException.class);
        assertThatThrownBy(() -> GiftCardCode.last4(null))
                .isInstanceOf(InvalidGiftCardStateException.class);
    }
}
