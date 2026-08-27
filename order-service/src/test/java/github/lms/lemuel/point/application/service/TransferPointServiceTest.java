package github.lms.lemuel.point.application.service;

import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase.PointTransferHistoryEntry;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase.TransferPointCommand;
import github.lms.lemuel.point.application.port.in.TransferPointUseCase.TransferPointResult;
import github.lms.lemuel.point.application.port.out.LoadTransferRecipientPort;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PointTransferPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotStatus;
import github.lms.lemuel.point.domain.PointTransfer;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import github.lms.lemuel.point.domain.exception.PointTransferRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("회원 간 포인트 선물 서비스")
class TransferPointServiceTest {

    private static final Long SENDER = 10L;
    private static final Long RECEIVER = 20L;
    private static final String TRANSFER_NO = "PT20260828-00000001";
    private static final OffsetDateTime NOW = OffsetDateTime.now();

    @Mock PointTransferPort transferPort;
    @Mock LoadTransferRecipientPort recipientPort;
    @Mock PointAccountPort accountPort;
    @Mock PointLotPort lotPort;
    @Mock PointEntryPort entryPort;
    @Mock PublishPointEventPort eventPort;

    TransferPointService service;

    private PointAccount senderAccount;
    private PointAccount receiverAccount;

    @BeforeEach
    void setUp() {
        service = new TransferPointService(transferPort, recipientPort, accountPort, lotPort, entryPort);

        senderAccount = account(100L, SENDER, "5000");
        receiverAccount = account(200L, RECEIVER, "0");

        when(transferPort.findBySenderAndRequestId(anyLong(), any())).thenReturn(Optional.empty());
        when(transferPort.nextTransferNo()).thenReturn(TRANSFER_NO);
        when(transferPort.save(any())).thenAnswer(invocation -> {
            PointTransfer transfer = invocation.getArgument(0);
            transfer.assignId(1L);
            return transfer;
        });
        when(recipientPort.findActiveRecipient(any(), any()))
                .thenReturn(Optional.of(new LoadTransferRecipientPort.Recipient(RECEIVER, "김받는")));
        when(accountPort.openIfAbsent(SENDER)).thenReturn(senderAccount);
        when(accountPort.openIfAbsent(RECEIVER)).thenReturn(receiverAccount);
        when(accountPort.loadForUpdate(SENDER)).thenReturn(Optional.of(senderAccount));
        when(accountPort.loadForUpdate(RECEIVER)).thenReturn(Optional.of(receiverAccount));
        when(accountPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(entryPort.nextSequence(anyLong(), any(), any(), any())).thenReturn(0);
        when(entryPort.append(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lotPort.loadConsumable(100L)).thenReturn(lots(lot(1L, "5000", null)));
        when(lotPort.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lotPort.save(any())).thenAnswer(invocation -> {
            PointLot lot = invocation.getArgument(0);
            lot.assignId(900L);
            return lot;
        });
    }

    private static PointAccount account(long id, long userId, String available) {
        BigDecimal amount = new BigDecimal(available);
        return PointAccount.rehydrate(id, userId, amount, BigDecimal.ZERO, amount,
                PointAccountStatus.ACTIVE, 0L, NOW, NOW);
    }

    private static PointLot lot(long id, String remaining, OffsetDateTime expiresAt) {
        BigDecimal amount = new BigDecimal(remaining);
        return PointLot.rehydrate(id, 100L, PointLotOrigin.ORDER_EARN, amount, amount,
                PointLotStatus.ACTIVE, NOW.minusDays(10), expiresAt, "ORDER", "o-" + id, 0L);
    }

    /** PointLotSelector 가 리스트를 제자리에서 고치므로 가변 리스트로 준다. */
    private static List<PointLot> lots(PointLot... lots) {
        return new ArrayList<>(List.of(lots));
    }

    private static TransferPointCommand command(String amount) {
        return new TransferPointCommand(SENDER, "req-1", "friend@example.com", "김받는",
                new BigDecimal(amount), "고마워");
    }

    @Nested
    @DisplayName("정상 선물")
    class HappyPath {

        @Test
        @DisplayName("보내는 이의 잔고가 줄고 받는 이의 잔고가 같은 만큼 는다")
        void movesBalance() {
            TransferPointResult result = service.transfer(command("1000"));

            assertThat(senderAccount.getAvailable()).isEqualByComparingTo("4000");
            assertThat(receiverAccount.getAvailable()).isEqualByComparingTo("1000");
            assertThat(result.amount()).isEqualByComparingTo("1000");
            assertThat(result.remainingBalance()).isEqualByComparingTo("4000");
            assertThat(result.transferNo()).isEqualTo(TRANSFER_NO);
            assertThat(result.alreadyProcessed()).isFalse();
        }

        @Test
        @DisplayName("총 포인트부채는 변하지 않는다 — 회사 밖으로 나간 것이 없다")
        void keepsTotalLiability() {
            BigDecimal before = senderAccount.getTotal().add(receiverAccount.getTotal());

            service.transfer(command("1000"));

            assertThat(senderAccount.getTotal().add(receiverAccount.getTotal()))
                    .isEqualByComparingTo(before);
        }

        @Test
        @DisplayName("이벤트를 하나도 발행하지 않는다 — 분개할 것이 없으면 이벤트도 없다")
        void publishesNoEvents() {
            service.transfer(command("1000"));

            verifyNoInteractions(eventPort);
        }

        @Test
        @DisplayName("보내는 이에게 USE, 받는 이에게 GRANT 를 남긴다 — 새 엔트리 유형을 만들지 않는다")
        void writesBothLedgerSides() {
            service.transfer(command("1000"));

            ArgumentCaptor<PointEntry> captor = ArgumentCaptor.forClass(PointEntry.class);
            verify(entryPort, org.mockito.Mockito.times(2)).append(captor.capture());
            List<PointEntry> entries = captor.getAllValues();

            assertThat(entries).extracting(PointEntry::getType)
                    .containsExactly(PointEntryType.USE, PointEntryType.GRANT);
            assertThat(entries).extracting(PointEntry::getReferenceType)
                    .containsOnly("POINT_TRANSFER");
            assertThat(entries).extracting(PointEntry::getReferenceId)
                    .containsOnly(TRANSFER_NO);
            assertThat(entries.get(0).getAccountId()).isEqualTo(100L);
            assertThat(entries.get(1).getAccountId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("받는 이의 로트 출처는 TRANSFER_IN 이다 — 판촉비를 두 번 잡지 않기 위해서다")
        void issuesTransferInLot() {
            service.transfer(command("1000"));

            ArgumentCaptor<PointLot> captor = ArgumentCaptor.forClass(PointLot.class);
            verify(lotPort).save(captor.capture());

            assertThat(captor.getValue().getOrigin()).isEqualTo(PointLotOrigin.TRANSFER_IN);
            assertThat(captor.getValue().getAccountId()).isEqualTo(200L);
            assertThat(captor.getValue().getOriginalAmount()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("선물 기록을 남기고 응답의 이메일은 마스킹한다")
        void savesTransferAndMasksEmail() {
            TransferPointResult result = service.transfer(command("1000"));

            verify(transferPort).save(any(PointTransfer.class));
            assertThat(result.recipientMaskedEmail()).isEqualTo("fr****@example.com");
            assertThat(result.recipientName()).isEqualTo("김받는");
        }
    }

    @Nested
    @DisplayName("유효기간 승계")
    class ExpiryInheritance {

        @Test
        @DisplayName("소비한 로트 중 가장 이른 만료일을 물려준다 — 선물로 기한을 되살릴 수 없다")
        void inheritsEarliestExpiry() {
            OffsetDateTime soon = NOW.plusDays(10);
            OffsetDateTime later = NOW.plusDays(100);
            when(lotPort.loadConsumable(100L))
                    .thenReturn(lots(lot(1L, "600", later), lot(2L, "600", soon)));

            service.transfer(command("1000"));

            ArgumentCaptor<PointLot> captor = ArgumentCaptor.forClass(PointLot.class);
            verify(lotPort).save(captor.capture());
            assertThat(captor.getValue().getExpiresAt()).isEqualTo(soon);
        }

        @Test
        @DisplayName("소비한 로트가 모두 무기한이면 새 로트도 무기한이다")
        void keepsNullExpiry() {
            service.transfer(command("1000"));

            ArgumentCaptor<PointLot> captor = ArgumentCaptor.forClass(PointLot.class);
            verify(lotPort).save(captor.capture());
            assertThat(captor.getValue().getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("이미 지난 만료일은 물려주지 않는다 — 새 로트의 만료일은 발급 시각보다 뒤여야 한다")
        void ignoresStaleExpiry() {
            when(lotPort.loadConsumable(100L))
                    .thenReturn(lots(lot(1L, "1000", NOW.minusDays(1))));

            service.transfer(command("1000"));

            ArgumentCaptor<PointLot> captor = ArgumentCaptor.forClass(PointLot.class);
            verify(lotPort).save(captor.capture());
            assertThat(captor.getValue().getExpiresAt()).isNull();
        }
    }

    @Nested
    @DisplayName("거절")
    class Rejections {

        @Test
        @DisplayName("받는 이를 못 찾으면 사유를 나누지 않고 하나로 거절한다")
        void rejectsUnknownRecipient() {
            when(recipientPort.findActiveRecipient(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transfer(command("1000")))
                    .isInstanceOf(PointTransferRejectedException.class)
                    .extracting(e -> ((PointTransferRejectedException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_TRANSFER_RECIPIENT_UNKNOWN);

            verify(transferPort, never()).save(any());
            verify(transferPort, never()).nextTransferNo();
        }

        @Test
        @DisplayName("받는 이가 나 자신으로 풀리면 거절한다")
        void rejectsSelfTransfer() {
            when(recipientPort.findActiveRecipient(any(), any()))
                    .thenReturn(Optional.of(new LoadTransferRecipientPort.Recipient(SENDER, "나")));

            assertThatThrownBy(() -> service.transfer(command("1000")))
                    .isInstanceOf(PointTransferRejectedException.class)
                    .extracting(e -> ((PointTransferRejectedException) e).getErrorCode())
                    .isEqualTo(ErrorCode.POINT_TRANSFER_SELF);
        }

        @Test
        @DisplayName("잔액을 넘으면 계정이 거절한다 — 잔액 판정의 주인은 하나다")
        void rejectsInsufficientBalance() {
            assertThatThrownBy(() -> service.transfer(command("9000")))
                    .isInstanceOf(InsufficientPointException.class);

            assertThat(receiverAccount.getAvailable()).isEqualByComparingTo("0");
            verify(transferPort, never()).save(any());
        }

        @Test
        @DisplayName("보내는 이가 없으면 거절한다")
        void rejectsMissingSender() {
            TransferPointCommand anonymous = new TransferPointCommand(null, "req-1",
                    "friend@example.com", "김받는", new BigDecimal("100"), null);

            assertThatThrownBy(() -> service.transfer(anonymous))
                    .isInstanceOf(PointTransferRejectedException.class);
        }
    }

    @Nested
    @DisplayName("멱등")
    class Idempotency {

        @Test
        @DisplayName("같은 요청 식별자로 다시 부르면 첫 결과를 돌려주고 잔고를 건드리지 않는다")
        void replaysWithoutMoving() {
            PointTransfer existing = PointTransfer.rehydrate(1L, TRANSFER_NO, "req-1",
                    SENDER, RECEIVER, new BigDecimal("1000"), "고마워", NOW);
            when(transferPort.findBySenderAndRequestId(SENDER, "req-1"))
                    .thenReturn(Optional.of(existing));
            when(accountPort.load(SENDER)).thenReturn(Optional.of(senderAccount));
            when(recipientPort.findNameById(RECEIVER)).thenReturn(Optional.of("김받는"));

            TransferPointResult result = service.transfer(command("1000"));

            assertThat(result.alreadyProcessed()).isTrue();
            assertThat(result.transferNo()).isEqualTo(TRANSFER_NO);
            assertThat(result.amount()).isEqualByComparingTo("1000");
            assertThat(senderAccount.getAvailable()).isEqualByComparingTo("5000");
            verify(transferPort, never()).save(any());
            verify(entryPort, never()).append(any());
        }
    }

    @Nested
    @DisplayName("이력")
    class History {

        @Test
        @DisplayName("보낸 것과 받은 것을 방향과 함께 돌려준다")
        void mixesBothDirections() {
            PointTransfer out = PointTransfer.rehydrate(1L, "PT-1", "r1", SENDER, RECEIVER,
                    new BigDecimal("100"), "선물", NOW);
            PointTransfer in = PointTransfer.rehydrate(2L, "PT-2", "r2", RECEIVER, SENDER,
                    new BigDecimal("200"), null, NOW);
            when(transferPort.findByParticipant(eq(SENDER), anyInt())).thenReturn(List.of(out, in));
            when(recipientPort.findNameById(RECEIVER)).thenReturn(Optional.of("김받는"));

            List<PointTransferHistoryEntry> entries = service.history(SENDER, 20);

            assertThat(entries).extracting(PointTransferHistoryEntry::outgoing)
                    .containsExactly(true, false);
            assertThat(entries).extracting(PointTransferHistoryEntry::counterpartName)
                    .containsExactly("김받는", "김받는");
            // 같은 상대와 두 번 오갔어도 이름은 한 번만 묻는다.
            verify(recipientPort, org.mockito.Mockito.times(1)).findNameById(RECEIVER);
        }

        @Test
        @DisplayName("탈퇴한 상대는 이름 대신 표시를 준다")
        void showsWithdrawnCounterpart() {
            PointTransfer out = PointTransfer.rehydrate(1L, "PT-1", "r1", SENDER, RECEIVER,
                    new BigDecimal("100"), null, NOW);
            when(transferPort.findByParticipant(eq(SENDER), anyInt())).thenReturn(List.of(out));
            when(recipientPort.findNameById(RECEIVER)).thenReturn(Optional.empty());

            assertThat(service.history(SENDER, 20))
                    .extracting(PointTransferHistoryEntry::counterpartName)
                    .containsExactly("(탈퇴한 회원)");
        }

        @Test
        @DisplayName("요청한 건수가 상한을 넘으면 상한으로 자른다")
        void capsLimit() {
            when(transferPort.findByParticipant(eq(SENDER), anyInt())).thenReturn(List.of());

            service.history(SENDER, 10_000);

            verify(transferPort).findByParticipant(SENDER, TransferPointService.MAX_HISTORY_LIMIT);
        }

        @Test
        @DisplayName("0 이하를 요청해도 최소 1건은 읽는다")
        void flooredLimit() {
            when(transferPort.findByParticipant(eq(SENDER), anyInt())).thenReturn(List.of());

            service.history(SENDER, 0);

            verify(transferPort).findByParticipant(SENDER, 1);
        }
    }
}
