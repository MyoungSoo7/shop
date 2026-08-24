package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardAmountException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GiftCard 도메인 단위 테스트.
 *
 * <p>포인트와 갈라지는 지점을 집중적으로 본다 — 잔액이 <b>증서 하나</b>에 붙고,
 * 귀속은 등록으로 생기며, 등록은 한 번뿐이다.
 */
class GiftCardTest {

    private static final OffsetDateTime ISSUED_AT =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRES_AT = ISSUED_AT.plusDays(365);
    private static final Long USER_ID = 42L;

    private GiftCard issued(String face) {
        return GiftCard.issue("hash-1", "9821", new BigDecimal(face), ISSUED_AT, EXPIRES_AT,
                "admin:1", "8월 프로모션");
    }

    private GiftCard registered(String face) {
        GiftCard card = issued(face);
        card.activate();
        card.registerTo(USER_ID, ISSUED_AT.plusDays(1));
        return card;
    }

    private static void assertInvariant(GiftCard card) {
        assertThat(card.getRemainingAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(card.getRemainingAmount()).isLessThanOrEqualTo(card.getFaceAmount());
    }

    @Nested
    @DisplayName("issue · activate — 발행과 활성화")
    class IssueTests {

        @Test
        @DisplayName("발행 직후 잔액은 권면가이고 아직 주인이 없다 — 부채가 아니다")
        void issue_startsUnowned() {
            GiftCard card = issued("50000");

            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.ISSUED);
            assertThat(card.getFaceAmount()).isEqualByComparingTo(new BigDecimal("50000"));
            assertThat(card.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("50000"));
            assertThat(card.getOwnerUserId()).isNull();
            assertInvariant(card);
        }

        @Test
        @DisplayName("권면가는 양수 정수여야 한다")
        void issue_rejectsInvalidFace() {
            assertThatThrownBy(() -> issued("0"))
                    .isInstanceOf(InvalidGiftCardAmountException.class);
            assertThatThrownBy(() -> issued("10000.5"))
                    .isInstanceOf(InvalidGiftCardAmountException.class);
        }

        @Test
        @DisplayName("유효기간이 발행일보다 이르면 거절한다")
        void issue_rejectsExpiryBeforeIssue() {
            assertThatThrownBy(() -> GiftCard.issue("hash-2", "1111", new BigDecimal("10000"),
                    ISSUED_AT, ISSUED_AT.minusDays(1), "admin:1", null))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }

        @Test
        @DisplayName("활성화해야 등록할 수 있다 — 발행 상태 카드는 등록 불가")
        void register_requiresActivation() {
            GiftCard card = issued("50000");

            assertThatThrownBy(() -> card.registerTo(USER_ID, ISSUED_AT.plusDays(1)))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }

        @Test
        @DisplayName("활성화 재적용은 멱등 no-op 이다")
        void activate_isIdempotent() {
            GiftCard card = issued("50000");

            card.activate();
            card.activate();

            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("registerTo — 등록(귀속)")
    class RegisterTests {

        @Test
        @DisplayName("등록하면 소유자가 생기고 결제에 쓸 수 있다")
        void register_assignsOwner() {
            GiftCard card = registered("50000");

            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.REGISTERED);
            assertThat(card.getOwnerUserId()).isEqualTo(USER_ID);
            assertThat(card.getRegisteredAt()).isNotNull();
            assertThat(card.isSpendableBy(USER_ID)).isTrue();
        }

        @Test
        @DisplayName("이미 등록된 카드는 다시 등록할 수 없다 — 코드를 아는 사람이 남의 잔액을 가져가는 경로를 막는다")
        void register_onlyOnce() {
            GiftCard card = registered("50000");

            assertThatThrownBy(() -> card.registerTo(99L, ISSUED_AT.plusDays(2)))
                    .isInstanceOf(InvalidGiftCardStateException.class);
            assertThat(card.getOwnerUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("소유자가 아니면 사용할 수 없다")
        void notSpendableByOthers() {
            GiftCard card = registered("50000");

            assertThat(card.isSpendableBy(99L)).isFalse();
        }
    }

    @Nested
    @DisplayName("use · restore — 사용과 복원")
    class UseTests {

        @Test
        @DisplayName("부분 사용이 가능하고 잔액이 이월된다 — 권면가 단위를 강제하지 않는다")
        void use_partial() {
            GiftCard card = registered("50000");

            card.use(new BigDecimal("30000"));

            assertThat(card.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("20000"));
            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.REGISTERED);
            assertInvariant(card);
        }

        @Test
        @DisplayName("전액 사용하면 USED_UP 으로 닫힌다 (경계값)")
        void use_exactBalanceClosesCard() {
            GiftCard card = registered("50000");

            card.use(new BigDecimal("50000"));

            assertThat(card.getRemainingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
        }

        @Test
        @DisplayName("잔액보다 1원이라도 많으면 거절한다 (경계값)")
        void use_rejectsOverBalance() {
            GiftCard card = registered("50000");

            assertThatThrownBy(() -> card.use(new BigDecimal("50001")))
                    .isInstanceOf(InsufficientGiftCardBalanceException.class);
            assertThat(card.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        }

        @Test
        @DisplayName("등록되지 않은 카드는 사용할 수 없다")
        void use_requiresRegistration() {
            GiftCard card = issued("50000");
            card.activate();

            assertThatThrownBy(() -> card.use(new BigDecimal("1000")))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }

        @Test
        @DisplayName("소진된 카드도 환불 복원으로 되살아난다 — 원래 유효기간이 유지된다")
        void restore_revivesUsedUpCard() {
            GiftCard card = registered("50000");
            card.use(new BigDecimal("50000"));

            card.restore(new BigDecimal("20000"));

            assertThat(card.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("20000"));
            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.REGISTERED);
            assertThat(card.getExpiresAt()).isEqualTo(EXPIRES_AT);
        }

        @Test
        @DisplayName("권면가를 넘겨 복원할 수 없다 — 없던 잔액이 생긴다")
        void restore_rejectsOverFace() {
            GiftCard card = registered("50000");
            card.use(new BigDecimal("10000"));

            assertThatThrownBy(() -> card.restore(new BigDecimal("20000")))
                    .isInstanceOf(GiftCardInvariantViolationException.class);
        }

        @Test
        @DisplayName("소멸된 카드에는 복원할 수 없다")
        void restore_rejectedWhenExpired() {
            GiftCard card = registered("50000");
            card.expire(EXPIRES_AT.plusSeconds(1));

            assertThatThrownBy(() -> card.restore(new BigDecimal("10000")))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }

    @Nested
    @DisplayName("expire · suspend — 소멸과 정지")
    class TerminalTests {

        @Test
        @DisplayName("소멸하면 남은 잔액을 반환하고 EXPIRED 로 닫힌다")
        void expire_returnsRemaining() {
            GiftCard card = registered("50000");
            card.use(new BigDecimal("20000"));

            BigDecimal forfeited = card.expire(EXPIRES_AT.plusSeconds(1));

            assertThat(forfeited).isEqualByComparingTo(new BigDecimal("30000"));
            assertThat(card.getRemainingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.EXPIRED);
        }

        @Test
        @DisplayName("만료 시각 정각은 아직 유효하다 (반열림 경계)")
        void notExpiredAtExactBoundary() {
            GiftCard card = registered("50000");

            assertThat(card.isExpiredAt(EXPIRES_AT)).isFalse();
            assertThat(card.isExpiredAt(EXPIRES_AT.plusNanos(1_000_000))).isTrue();
            assertThatThrownBy(() -> card.expire(EXPIRES_AT))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }

        @Test
        @DisplayName("정지된 카드는 사용할 수 없다 — 분실·부정 신고 대응")
        void suspend_blocksUse() {
            GiftCard card = registered("50000");

            card.suspend();

            assertThat(card.getStatus()).isEqualTo(GiftCardStatus.SUSPENDED);
            assertThat(card.isSpendableBy(USER_ID)).isFalse();
            assertThatThrownBy(() -> card.use(new BigDecimal("1000")))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }

        @Test
        @DisplayName("이미 소멸한 카드는 다시 소멸시킬 수 없다")
        void expire_isNotRepeatable() {
            GiftCard card = registered("50000");
            card.expire(EXPIRES_AT.plusDays(1));

            assertThatThrownBy(() -> card.expire(EXPIRES_AT.plusDays(2)))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }

    @Test
    @DisplayName("rehydrate 로 복원한 카드도 불변식을 검증한다")
    void rehydrate_validatesInvariant() {
        assertThatThrownBy(() -> GiftCard.rehydrate(1L, "hash-1", "9821",
                new BigDecimal("10000"), new BigDecimal("20000"), GiftCardStatus.REGISTERED,
                USER_ID, ISSUED_AT, ISSUED_AT, ISSUED_AT, EXPIRES_AT, "admin:1", null, 0L))
                .isInstanceOf(GiftCardInvariantViolationException.class);
    }

    @Test
    @DisplayName("등록 상태인데 소유자가 없으면 불변식 위반이다 — 주인 없는 잔액을 만들지 않는다")
    void rehydrate_rejectsRegisteredWithoutOwner() {
        assertThatThrownBy(() -> GiftCard.rehydrate(1L, "hash-1", "9821",
                new BigDecimal("10000"), new BigDecimal("10000"), GiftCardStatus.REGISTERED,
                null, ISSUED_AT, ISSUED_AT, ISSUED_AT, EXPIRES_AT, "admin:1", null, 0L))
                .isInstanceOf(GiftCardInvariantViolationException.class);
    }
}
