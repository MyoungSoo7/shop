package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.InvalidGiftClaimStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GiftClaim — 선물 수령")
class GiftClaimTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

    private static GiftClaim open() {
        return GiftClaim.open(1L, 2L, "김수령", "010-1234-5678", "생일 축하해",
                "tokenhash", NOW, NOW.plusDays(14));
    }

    /** PENDING → VERIFIED 까지 밀어 둔 링크. */
    private static GiftClaim verified() {
        GiftClaim claim = open();
        claim.issueVerificationCode("codehash", NOW, NOW.plusMinutes(5));
        claim.verify("codehash", NOW.plusMinutes(1));
        return claim;
    }

    @Nested
    @DisplayName("발급")
    class Opening {

        @Test
        @DisplayName("이미 지난 시각을 기한으로 주면 만들어지지 않는다 — 태어나자마자 죽은 링크")
        void rejectsPastExpiry() {
            assertThatThrownBy(() -> GiftClaim.open(1L, 2L, "김수령", "010-1234-5678", null,
                    "tokenhash", NOW, NOW.minusSeconds(1)))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test
        @DisplayName("숫자가 9개 미만인 번호는 받지 않는다 — 보낼 수 없는 번호로는 선물이 성립하지 않는다")
        void rejectsShortPhone() {
            assertThatThrownBy(() -> GiftClaim.open(1L, 2L, "김수령", "010-1234",
                    null, "tokenhash", NOW, NOW.plusDays(1)))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test
        @DisplayName("PENDING 으로 시작하고 시도 횟수는 0")
        void startsPending() {
            GiftClaim claim = open();
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.PENDING);
            assertThat(claim.getVerifyAttempts()).isZero();
            assertThat(claim.isActionable(NOW)).isTrue();
        }
    }

    @Nested
    @DisplayName("본인확인")
    class Verification {

        @Test
        @DisplayName("맞는 번호를 넣으면 VERIFIED 가 되고 쓴 번호는 즉시 버려진다")
        void verifySucceeds() {
            GiftClaim claim = verified();
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.VERIFIED);
            assertThat(claim.getVerifiedAt()).isNotNull();
            // 남겨 두면 유출 시 재사용된다.
            assertThat(claim.getVerificationCodeHash()).isNull();
            assertThat(claim.getCodeExpiresAt()).isNull();
        }

        @Test
        @DisplayName("틀리면 시도 횟수가 오르고, 남은 횟수가 메시지에 담긴다")
        void wrongCodeCountsUp() {
            GiftClaim claim = open();
            claim.issueVerificationCode("codehash", NOW, NOW.plusMinutes(5));

            assertThatThrownBy(() -> claim.verify("nope", NOW.plusMinutes(1)))
                    .isInstanceOf(InvalidGiftClaimStateException.class)
                    .hasMessageContaining("남은 시도 4");
            assertThat(claim.getVerifyAttempts()).isEqualTo(1);
            // 실패해도 링크 자체는 살아 있다 — 오타 한 번이 선물을 죽이지는 않는다.
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.PENDING);
        }

        @Test
        @DisplayName("5번 틀리면 맞는 번호를 넣어도 잠긴다 — 6자리를 지키는 실제 수단")
        void locksAfterMaxAttempts() {
            GiftClaim claim = open();
            claim.issueVerificationCode("codehash", NOW, NOW.plusMinutes(5));
            for (int i = 0; i < GiftClaim.MAX_VERIFY_ATTEMPTS; i++) {
                assertThatThrownBy(() -> claim.verify("nope", NOW.plusMinutes(1)))
                        .isInstanceOf(InvalidGiftClaimStateException.class);
            }
            assertThatThrownBy(() -> claim.verify("codehash", NOW.plusMinutes(1)))
                    .isInstanceOf(InvalidGiftClaimStateException.class)
                    .hasMessageContaining("너무 많이");
        }

        @Test
        @DisplayName("번호를 다시 받으면 시도 횟수가 0 으로 돌아간다")
        void reissueResetsAttempts() {
            GiftClaim claim = open();
            claim.issueVerificationCode("codehash", NOW, NOW.plusMinutes(5));
            assertThatThrownBy(() -> claim.verify("nope", NOW.plusMinutes(1)))
                    .isInstanceOf(InvalidGiftClaimStateException.class);

            claim.issueVerificationCode("newhash", NOW.plusMinutes(2), NOW.plusMinutes(7));
            assertThat(claim.getVerifyAttempts()).isZero();
            // 이전 번호는 그 자리에서 죽는다 — 두 번호가 동시에 유효하면 안 된다.
            assertThatThrownBy(() -> claim.verify("codehash", NOW.plusMinutes(3)))
                    .isInstanceOf(InvalidGiftClaimStateException.class);
        }

        @Test
        @DisplayName("유효시간이 지난 번호는 맞아도 통과하지 않는다")
        void expiredCodeRejected() {
            GiftClaim claim = open();
            claim.issueVerificationCode("codehash", NOW, NOW.plusMinutes(5));
            assertThatThrownBy(() -> claim.verify("codehash", NOW.plusMinutes(6)))
                    .isInstanceOf(InvalidGiftClaimStateException.class)
                    .hasMessageContaining("유효시간");
        }

        @Test
        @DisplayName("발송된 번호가 없는데 확인부터 하면 거절된다")
        void verifyWithoutCode() {
            assertThatThrownBy(() -> open().verify("codehash", NOW))
                    .isInstanceOf(InvalidGiftClaimStateException.class);
        }
    }

    @Nested
    @DisplayName("수령")
    class Claiming {

        @Test
        @DisplayName("본인확인을 건너뛰고는 수령할 수 없다 — 링크를 주운 사람이 배송지를 바꾸는 길")
        void cannotClaimWithoutVerification() {
            assertThatThrownBy(() -> open().markClaimed(NOW))
                    .isInstanceOf(InvalidGiftClaimStateException.class);
        }

        @Test
        @DisplayName("본인확인 뒤에는 수령된다")
        void claimAfterVerification() {
            GiftClaim claim = verified();
            claim.markClaimed(NOW.plusMinutes(2));
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.CLAIMED);
            assertThat(claim.getClaimedAt()).isEqualTo(NOW.plusMinutes(2));
        }

        @Test
        @DisplayName("두 번 수령할 수 없다")
        void claimIsOnce() {
            GiftClaim claim = verified();
            claim.markClaimed(NOW.plusMinutes(2));
            assertThatThrownBy(() -> claim.markClaimed(NOW.plusMinutes(3)))
                    .isInstanceOf(InvalidGiftClaimStateException.class);
        }
    }

    @Nested
    @DisplayName("만료 — 배치가 아니라 시각이 판정한다")
    class Expiry {

        @Test
        @DisplayName("기한이 지나면 상태가 PENDING 이어도 아무것도 할 수 없다")
        void timeAloneExpires() {
            GiftClaim claim = open();
            LocalDateTime late = NOW.plusDays(15);

            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.PENDING);
            assertThat(claim.isExpired(late)).isTrue();
            assertThat(claim.isActionable(late)).isFalse();
            assertThatThrownBy(() -> claim.issueVerificationCode("codehash", late, late.plusMinutes(5)))
                    .isInstanceOf(InvalidGiftClaimStateException.class)
                    .hasMessageContaining("유효기간");
        }

        @Test
        @DisplayName("배치가 EXPIRED 로 남기는 것은 기록일 뿐 — 판정은 이미 끝나 있다")
        void batchOnlyRecords() {
            GiftClaim claim = open();
            claim.expire(NOW.plusDays(15));
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.EXPIRED);
            assertThat(claim.isActionable(NOW.plusDays(15))).isFalse();
        }

        @ParameterizedTest(name = "{0} 에서는 아무 데로도 못 간다")
        @EnumSource(value = GiftClaimStatus.class, names = {"CLAIMED", "EXPIRED", "CANCELED"})
        void terminalIsTerminal(GiftClaimStatus terminal) {
            assertThat(terminal.isTerminal()).isTrue();
            for (GiftClaimStatus target : GiftClaimStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).isFalse();
            }
        }

        @Test
        @DisplayName("PENDING 에서 CLAIMED 로 직행하는 길은 닫혀 있다")
        void noSkippingVerification() {
            assertThat(GiftClaimStatus.PENDING.canTransitionTo(GiftClaimStatus.CLAIMED)).isFalse();
            assertThat(GiftClaimStatus.PENDING.canTransitionTo(GiftClaimStatus.VERIFIED)).isTrue();
        }
    }

    @Nested
    @DisplayName("재발송")
    class Rotation {

        @Test
        @DisplayName("토큰을 갈아 끼우면 옛 토큰은 죽고 인증번호도 함께 버려진다")
        void rotateInvalidatesEverything() {
            GiftClaim claim = open();
            claim.issueVerificationCode("codehash", NOW, NOW.plusMinutes(5));

            claim.rotateToken("newtokenhash", NOW.plusMinutes(1));

            assertThat(claim.getTokenHash()).isEqualTo("newtokenhash");
            assertThat(claim.getVerificationCodeHash()).isNull();
            assertThat(claim.getVerifyAttempts()).isZero();
            // 상태·기한·받는 사람은 그대로 간다 — 같은 선물이다.
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.PENDING);
            assertThat(claim.getExpiresAt()).isEqualTo(NOW.plusDays(14));
        }

        @Test
        @DisplayName("이미 끝난 선물은 재발송되지 않는다 — 그건 새로 보내야 하는 것이다")
        void cannotRotateTerminal() {
            GiftClaim claim = open();
            claim.cancel(NOW.plusMinutes(1));
            assertThatThrownBy(() -> claim.rotateToken("newtokenhash", NOW.plusMinutes(2)))
                    .isInstanceOf(InvalidGiftClaimStateException.class);
        }
    }

    @Nested
    @DisplayName("연락처 마스킹")
    class Masking {

        @Test
        @DisplayName("가운데를 가리되 뒤 4자리는 남긴다 — 어디로 가는지는 확인시켜 줘야 한다")
        void masksMiddle() {
            assertThat(open().maskedRecipientPhone()).isEqualTo("010-****-5678");
        }

        @Test
        @DisplayName("형태를 알 수 없는 짧은 번호는 통째로 가린다 — 가릴 자리가 없으면 다 가린다")
        void masksUnknownShape() {
            // open() 은 9자리 미만을 막지만 restore() 는 막지 않는다. 옛 데이터가 그렇게 들어와도
            // 마스킹이 번호를 흘리지는 않아야 한다.
            GiftClaim claim = GiftClaim.restore(1L, 1L, 2L, "김수령", "12345", null,
                    "tokenhash", GiftClaimStatus.PENDING, null, null, 0,
                    NOW.plusDays(1), NOW, null, null, NOW);
            assertThat(claim.maskedRecipientPhone()).isEqualTo("***");
        }
    }
}
