package github.lms.lemuel.giftcard.application.service;

import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase.ExpireGiftCardsCommand;
import github.lms.lemuel.giftcard.application.port.in.ExpireGiftCardsUseCase.ExpireGiftCardsResult;
import github.lms.lemuel.giftcard.application.port.in.IssueGiftCardsUseCase.IssueGiftCardsCommand;
import github.lms.lemuel.giftcard.application.port.in.IssueGiftCardsUseCase.IssuedGiftCard;
import github.lms.lemuel.giftcard.application.port.in.RegisterGiftCardUseCase.RegisterGiftCardCommand;
import github.lms.lemuel.giftcard.application.port.in.RegisterGiftCardUseCase.RegisterGiftCardResult;
import github.lms.lemuel.giftcard.application.port.in.RestoreGiftCardUseCase.RestoreGiftCardCommand;
import github.lms.lemuel.giftcard.application.port.in.UseGiftCardUseCase.UseGiftCardCommand;
import github.lms.lemuel.giftcard.application.port.in.UseGiftCardUseCase.UseGiftCardResult;
import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.application.port.out.PublishGiftCardEventPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardCode;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import github.lms.lemuel.giftcard.domain.exception.InsufficientGiftCardBalanceException;
import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기프트카드 응용 서비스 단위 테스트 — 발행·등록·사용·복원·소멸.
 *
 * <p>포인트와 다른 지점을 집중적으로 본다: 코드 실패 응답이 사유를 흘리지 않는가, 등록이 한 번뿐인가,
 * 사용이 카드마다 원장을 남기는가.
 */
class GiftCardServiceTest {

    private static final Long USER_ID = 42L;
    private static final OffsetDateTime ISSUED_AT = OffsetDateTime.parse("2026-08-01T00:00:00Z");
    private static final OffsetDateTime EXPIRES_AT = ISSUED_AT.plusDays(365);

    private GiftCardPort cardPort;
    private GiftCardEntryPort entryPort;
    private PublishGiftCardEventPort eventPort;

    @BeforeEach
    void setUp() {
        cardPort = mock(GiftCardPort.class);
        entryPort = mock(GiftCardEntryPort.class);
        eventPort = mock(PublishGiftCardEventPort.class);

        AtomicLong ids = new AtomicLong(100);
        when(cardPort.save(any())).thenAnswer(call -> {
            GiftCard card = call.getArgument(0);
            if (card.getId() == null) {
                card.assignId(ids.incrementAndGet());
            }
            return card;
        });
        when(cardPort.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        when(cardPort.existsByCodeHash(anyString())).thenReturn(false);
        when(cardPort.loadSpendableReadOnly(anyLong())).thenReturn(List.of());
        when(entryPort.nextSequence(anyLong(), any(), anyString(), anyString())).thenReturn(0);
        when(entryPort.existsByReference(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(entryPort.append(any())).thenAnswer(call -> {
            GiftCardEntry entry = call.getArgument(0);
            entry.assignId(900L);
            return entry;
        });
    }

    private GiftCard card(long id, String face, GiftCardStatus status, OffsetDateTime expiresAt) {
        GiftCard c = GiftCard.issue("hash-" + id, "000" + id, new BigDecimal(face),
                ISSUED_AT, expiresAt, "admin:1", null);
        c.assignId(id);
        if (status != GiftCardStatus.ISSUED) {
            c.activate();
        }
        if (status == GiftCardStatus.REGISTERED) {
            c.registerTo(USER_ID, ISSUED_AT.plusDays(1));
        }
        return c;
    }

    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("발행")
    class IssueTests {

        @Test
        @DisplayName("요청 장수만큼 발행하고 평문 코드를 한 번만 돌려준다")
        void issuesRequestedQuantity() {
            IssueGiftCardsService service = new IssueGiftCardsService(cardPort);

            List<IssuedGiftCard> issued = service.issue(new IssueGiftCardsCommand(
                    3, new BigDecimal("50000"), 365, true, "admin:1", "프로모션"));

            assertThat(issued).hasSize(3);
            assertThat(issued).allSatisfy(card -> {
                assertThat(card.code()).matches("^GC-[0-9A-HJKMNP-TV-Z]{16}$");
                assertThat(card.faceAmount()).isEqualByComparingTo(new BigDecimal("50000"));
            });
            // 코드가 서로 달라야 한다 — 같은 코드를 여러 장 찍으면 한 장만 등록된다.
            assertThat(issued.stream().map(IssuedGiftCard::code).distinct()).hasSize(3);
        }

        @Test
        @DisplayName("발행에는 회계 이벤트가 없다 — 아직 아무에게도 가지 않은 코드는 빚이 아니다")
        void issueEmitsNoLedgerEvent() {
            IssueGiftCardsService service = new IssueGiftCardsService(cardPort);

            service.issue(new IssueGiftCardsCommand(2, new BigDecimal("10000"), 30, true, "admin:1", null));

            verify(eventPort, never()).giftCardRegistered(any(), any());
        }

        @Test
        @DisplayName("장수 상한을 넘으면 거절한다 — 실수로 10만 장을 찍는 사고를 막는다")
        void rejectsOverMaxQuantity() {
            IssueGiftCardsService service = new IssueGiftCardsService(cardPort);

            assertThatThrownBy(() -> service.issue(new IssueGiftCardsCommand(
                    1001, new BigDecimal("10000"), 30, true, "admin:1", null)))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }

    @Nested
    @DisplayName("등록")
    class RegisterTests {

        private RegisterGiftCardService service;

        @BeforeEach
        void init() {
            service = new RegisterGiftCardService(cardPort, entryPort, eventPort);
        }

        @Test
        @DisplayName("등록하면 소유자가 생기고 부채 이벤트가 나간다 — 부채가 생기는 유일한 지점")
        void registersAndPublishes() {
            GiftCard target = card(1L, "50000", GiftCardStatus.ACTIVE, EXPIRES_AT);
            when(cardPort.loadByCodeHashForUpdate(anyString())).thenReturn(Optional.of(target));

            RegisterGiftCardResult result = service.register(
                    new RegisterGiftCardCommand("GC-ABCD2345EFGH6789", USER_ID, "user:42"));

            assertThat(result.faceAmount()).isEqualByComparingTo(new BigDecimal("50000"));
            assertThat(target.getOwnerUserId()).isEqualTo(USER_ID);
            verify(eventPort).giftCardRegistered(any(GiftCard.class), any(GiftCardEntry.class));
        }

        @Test
        @DisplayName("없는 코드와 이미 등록된 코드가 같은 응답을 낸다 — 코드 존재 여부를 흘리지 않는다")
        void failureDoesNotLeakExistence() {
            when(cardPort.loadByCodeHashForUpdate(anyString())).thenReturn(Optional.empty());
            Throwable unknown = org.assertj.core.api.Assertions.catchThrowable(() ->
                    service.register(new RegisterGiftCardCommand("GC-11112222333344AA", USER_ID, "u")));

            GiftCard alreadyRegistered = card(2L, "50000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            when(cardPort.loadByCodeHashForUpdate(anyString()))
                    .thenReturn(Optional.of(alreadyRegistered));
            Throwable taken = org.assertj.core.api.Assertions.catchThrowable(() ->
                    service.register(new RegisterGiftCardCommand("GC-11112222333344AA", 99L, "u")));

            assertThat(unknown).isInstanceOf(InvalidGiftCardStateException.class);
            assertThat(taken).isInstanceOf(InvalidGiftCardStateException.class);
            assertThat(unknown.getMessage()).isEqualTo(taken.getMessage());
        }

        @Test
        @DisplayName("만료된 카드는 등록할 수 없다")
        void rejectsExpiredCard() {
            GiftCard expired = card(3L, "50000", GiftCardStatus.ACTIVE, ISSUED_AT.plusDays(1));
            when(cardPort.loadByCodeHashForUpdate(anyString())).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.register(
                    new RegisterGiftCardCommand("GC-ABCD2345EFGH6789", USER_ID, "u")))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }

    @Nested
    @DisplayName("사용")
    class UseTests {

        private UseGiftCardService service;

        @BeforeEach
        void init() {
            service = new UseGiftCardService(cardPort, entryPort, eventPort, noGiftCardHolds());
        }

        @Test
        @DisplayName("여러 장에 걸쳐 쓰면 카드마다 원장 엔트리가 하나씩 생긴다")
        void oneEntryPerCard() {
            GiftCard first = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT.minusDays(100));
            GiftCard second = card(2L, "30000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            when(cardPort.loadSpendable(USER_ID)).thenReturn(List.of(first, second));

            UseGiftCardResult result = service.use(new UseGiftCardCommand(
                    USER_ID, new BigDecimal("25000"), "PAYMENT_TENDER", "77", "user:42"));

            assertThat(result.cardCount()).isEqualTo(2);
            assertThat(first.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
            assertThat(second.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("15000"));
            verify(entryPort, times(2)).append(any());
            verify(eventPort, times(2)).giftCardUsed(any(), any());
        }

        @Test
        @DisplayName("잔액을 넘는 사용은 거절하고 아무것도 저장하지 않는다")
        void rejectsInsufficientBalance() {
            GiftCard only = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            when(cardPort.loadSpendable(USER_ID)).thenReturn(List.of(only));

            assertThatThrownBy(() -> service.use(new UseGiftCardCommand(
                    USER_ID, new BigDecimal("10001"), "PAYMENT_TENDER", "77", "user:42")))
                    .isInstanceOf(InsufficientGiftCardBalanceException.class);

            verify(entryPort, never()).append(any());
            verify(cardPort, never()).saveAll(any());
        }

        @Test
        @DisplayName("같은 tender 로 두 번 쓰면 두 번째는 멱등 단축 반환한다")
        void isIdempotent() {
            GiftCard only = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            when(cardPort.loadSpendable(USER_ID)).thenReturn(List.of(only));
            when(entryPort.existsByReference(1L, GiftCardEntryType.USE, "PAYMENT_TENDER", "77"))
                    .thenReturn(true);

            UseGiftCardResult result = service.use(new UseGiftCardCommand(
                    USER_ID, new BigDecimal("5000"), "PAYMENT_TENDER", "77", "user:42"));

            assertThat(result.cardCount()).isZero();
            assertThat(only.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("10000"));
            verify(entryPort, never()).append(any());
        }
    }

    @Nested
    @DisplayName("복원")
    class RestoreTests {

        private RestoreGiftCardService service;

        @BeforeEach
        void init() {
            service = new RestoreGiftCardService(cardPort, entryPort, eventPort);
        }

        private void givenUsage(long cardId, String amount) {
            GiftCardEntry used = GiftCardEntry.use(cardId, new BigDecimal(amount),
                    "PAYMENT_TENDER", "77", 0, "user:42");
            when(entryPort.loadByReference(GiftCardEntryType.USE, "PAYMENT_TENDER", "77"))
                    .thenReturn(List.of(used));
        }

        @Test
        @DisplayName("사용한 카드로 되돌린다 — 소진된 카드도 되살아난다")
        void restoresToOriginalCard() {
            givenUsage(1L, "10000");
            GiftCard target = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            target.use(new BigDecimal("10000"));
            when(cardPort.loadForUpdate(anyCollection())).thenReturn(List.of(target));

            service.restore(new RestoreGiftCardCommand(new BigDecimal("10000"),
                    "PAYMENT_TENDER", "77", "PAYMENT_TENDER_REFUND", "tender-77-10000", "system"));

            assertThat(target.getRemainingAmount()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(target.getStatus()).isEqualTo(GiftCardStatus.REGISTERED);
            verify(eventPort).giftCardRestored(any(), any());
        }

        @Test
        @DisplayName("소멸된 카드에는 되돌리지 않는다 — 유효기간이 무의미해진다")
        void skipsExpiredCard() {
            givenUsage(1L, "10000");
            GiftCard expired = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            expired.use(new BigDecimal("10000"));
            expired.restore(new BigDecimal("10000"));
            expired.expire(EXPIRES_AT.plusDays(1));
            when(cardPort.loadForUpdate(anyCollection())).thenReturn(List.of(expired));

            assertThatThrownBy(() -> service.restore(new RestoreGiftCardCommand(
                    new BigDecimal("10000"), "PAYMENT_TENDER", "77",
                    "PAYMENT_TENDER_REFUND", "tender-77-10000", "system")))
                    .isInstanceOf(InvalidGiftCardStateException.class);
            verify(eventPort, never()).giftCardRestored(any(), any());
        }

        @Test
        @DisplayName("사용 이력이 없으면 복원할 수 없다")
        void rejectsWithoutUsage() {
            when(entryPort.loadByReference(GiftCardEntryType.USE, "PAYMENT_TENDER", "77"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.restore(new RestoreGiftCardCommand(
                    new BigDecimal("1000"), "PAYMENT_TENDER", "77",
                    "PAYMENT_TENDER_REFUND", "r", "system")))
                    .isInstanceOf(InvalidGiftCardStateException.class);
        }
    }

    @Nested
    @DisplayName("소멸")
    class ExpireTests {

        private ExpireGiftCardsService service;
        private final OffsetDateTime now = EXPIRES_AT.plusDays(1);

        @BeforeEach
        void init() {
            service = new ExpireGiftCardsService(cardPort, entryPort, eventPort);
        }

        @Test
        @DisplayName("dry-run 은 규모만 알려 주고 아무것도 바꾸지 않는다")
        void dryRunChangesNothing() {
            GiftCard target = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            when(cardPort.loadExpired(now, 100)).thenReturn(List.of(target));

            ExpireGiftCardsResult result = service.expire(
                    new ExpireGiftCardsCommand(now, 100, true, "batch"));

            assertThat(result.dryRun()).isTrue();
            assertThat(result.forfeitedTotal()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(target.getStatus()).isEqualTo(GiftCardStatus.REGISTERED);
            verify(entryPort, never()).append(any());
        }

        @Test
        @DisplayName("실행하면 카드가 닫히고 잔액이 소멸하며 원장이 남는다")
        void expiresAndRecords() {
            GiftCard target = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            when(cardPort.loadExpired(now, 100)).thenReturn(List.of(target));

            ExpireGiftCardsResult result = service.expire(
                    new ExpireGiftCardsCommand(now, 100, false, "batch"));

            assertThat(result.forfeitedTotal()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(target.getStatus()).isEqualTo(GiftCardStatus.EXPIRED);
            verify(entryPort).append(any());
            verify(eventPort).giftCardExpired(any(), any());
        }

        @Test
        @DisplayName("이미 닫힌 카드가 배치에 섞여도 건너뛴다 — 한 행 때문에 야간 배치가 죽지 않는다")
        void alreadyClosedCardIsSkipped() {
            GiftCard target = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
            target.use(new BigDecimal("10000"));   // USED_UP — 소멸 대상이 아니다
            when(cardPort.loadExpired(now, 100)).thenReturn(List.of(target));

            ExpireGiftCardsResult result = service.expire(
                    new ExpireGiftCardsCommand(now, 100, false, "batch"));

            assertThat(result.forfeitedTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(target.getStatus()).isEqualTo(GiftCardStatus.USED_UP);
            verify(entryPort, never()).append(any());
        }
    }

    @Test
    @DisplayName("코드 해시가 이미 있으면 다른 코드를 다시 뽑는다 — 충돌이 남의 카드를 덮어쓰지 않게")
    void issueRetriesOnHashCollision() {
        when(cardPort.existsByCodeHash(anyString())).thenReturn(true, false);
        IssueGiftCardsService service = new IssueGiftCardsService(cardPort);

        List<IssuedGiftCard> issued = service.issue(new IssueGiftCardsCommand(
                1, new BigDecimal("10000"), 30, true, "admin:1", null));

        assertThat(issued).hasSize(1);
        verify(cardPort, times(2)).existsByCodeHash(anyString());
    }

    @Test
    @DisplayName("등록 응답의 잔액은 그 사용자의 전체 상품권 잔액이다")
    void registerResultCarriesTotalBalance() {
        GiftCard target = card(1L, "50000", GiftCardStatus.ACTIVE, EXPIRES_AT);
        when(cardPort.loadByCodeHashForUpdate(anyString())).thenReturn(Optional.of(target));
        when(cardPort.loadSpendableReadOnly(USER_ID)).thenReturn(List.of(
                card(2L, "20000", GiftCardStatus.REGISTERED, EXPIRES_AT)));

        RegisterGiftCardResult result = new RegisterGiftCardService(cardPort, entryPort, eventPort)
                .register(new RegisterGiftCardCommand(GiftCardCode.generate(), USER_ID, "user:42"));

        assertThat(result.totalBalance()).isEqualByComparingTo(new BigDecimal("20000"));
    }

    @Test
    @DisplayName("사용 시 만료 임박 카드부터 쓴다 — 고객 손실을 줄이는 순서")
    void usesEarliestExpiryFirst() {
        GiftCard later = card(1L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT);
        GiftCard sooner = card(2L, "10000", GiftCardStatus.REGISTERED, EXPIRES_AT.minusDays(200));
        when(cardPort.loadSpendable(USER_ID)).thenReturn(List.of(later, sooner));

        new UseGiftCardService(cardPort, entryPort, eventPort, noGiftCardHolds()).use(new UseGiftCardCommand(
                USER_ID, new BigDecimal("5000"), "PAYMENT_TENDER", "77", "user:42"));

        ArgumentCaptor<GiftCardEntry> captor = ArgumentCaptor.forClass(GiftCardEntry.class);
        verify(entryPort).append(captor.capture());
        assertThat(captor.getValue().getGiftCardId()).isEqualTo(2L);
    }

    /** 선점이 없는 상태 — 이 테스트들은 즉시 사용 경로만 본다. */
    private static github.lms.lemuel.giftcard.application.port.out.GiftCardHoldPort noGiftCardHolds() {
        return new github.lms.lemuel.giftcard.application.port.out.GiftCardHoldPort() {
            @Override public java.util.List<github.lms.lemuel.giftcard.domain.GiftCardHold> saveHolds(
                    java.util.List<github.lms.lemuel.giftcard.domain.GiftCardHold> holds) { return holds; }
            @Override public github.lms.lemuel.giftcard.domain.GiftCardHold save(
                    github.lms.lemuel.giftcard.domain.GiftCardHold hold) { return hold; }
            @Override public java.util.List<github.lms.lemuel.giftcard.domain.GiftCardHold> findByReference(
                    String referenceType, String referenceId) { return java.util.List.of(); }
            @Override public java.util.List<Long> findCardIdsByReference(
                    String referenceType, String referenceId) { return java.util.List.of(); }
            @Override public java.util.Map<Long, java.math.BigDecimal> activeAmountsByCardIds(
                    java.util.Collection<Long> cardIds) { return java.util.Map.of(); }
        };
    }
}
