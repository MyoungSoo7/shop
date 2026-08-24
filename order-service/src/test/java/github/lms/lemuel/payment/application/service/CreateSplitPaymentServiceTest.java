package github.lms.lemuel.payment.application.service;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import github.lms.lemuel.payment.application.port.in.CreateSplitPaymentUseCase.TenderRequest;
import github.lms.lemuel.payment.application.port.out.LoadSellerSettlementMetaPort;
import github.lms.lemuel.payment.application.port.out.PgClientPort;
import github.lms.lemuel.payment.application.port.out.PublishEventPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import github.lms.lemuel.payment.application.port.out.UpdateOrderStatusPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentStatus;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateSplitPaymentService — 텐더 기반 결제 생성")
class CreateSplitPaymentServiceTest {

    @Mock PgClientPort pgClientPort;
    @Mock SavePaymentPort savePaymentPort;
    @Mock UpdateOrderStatusPort updateOrderStatusPort;
    @Mock PublishEventPort publishEventPort;
    @Mock LoadSellerSettlementMetaPort loadSellerSettlementMetaPort;
    @Mock github.lms.lemuel.payment.application.port.out.PointTenderPort pointTenderPort;
    @Mock github.lms.lemuel.payment.application.port.out.GiftCardTenderPort giftCardTenderPort;
    @InjectMocks CreateSplitPaymentService service;

    private static final Long ACTOR_USER_ID = 42L;

    @Test
    @DisplayName("외부 PG + 내부 잔액 tender 혼합 → CAPTURED 저장 + 주문 PAID + 이벤트 발행")
    void createWithTenders_mixedTenders() {
        when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("PGTX-1");
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any()))
                .thenReturn(Optional.of(new SellerSettlementMeta(9L, "VIP", "T+3")));

        PaymentDomain result = service.createWithTenders(100L, List.of(
                new TenderRequest(TenderType.CARD, new BigDecimal("35000")),
                new TenderRequest(TenderType.POINT, new BigDecimal("5000"))), ACTOR_USER_ID);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(result.getAmount()).isEqualByComparingTo("40000");
        assertThat(result.getTenders()).hasSize(2);
        verify(pgClientPort).authorize(eq(100L), any(), eq("CARD"));
        verify(pgClientPort).capture(eq("PGTX-1"), any());
        verify(updateOrderStatusPort).updateOrderStatus(100L, "PAID");
        verify(publishEventPort).publishPaymentCaptured(any(), eq(100L), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("모두 내부 잔액 tender 면 PG 호출 없음")
    void createWithTenders_allInternal() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        PaymentDomain result = service.createWithTenders(200L, List.of(
                new TenderRequest(TenderType.POINT, new BigDecimal("3000")),
                new TenderRequest(TenderType.GIFT_CARD, new BigDecimal("2000"))), ACTOR_USER_ID);

        assertThat(result.getAmount()).isEqualByComparingTo("5000");
        verify(pgClientPort, never()).authorize(anyLong(), any(), anyString());
    }

    /**
     * 포인트 전액 결제 — 이 경로가 열리기 전에는 포인트 원장을 다 만들어 놓고도
     * "포인트만으로 결제"가 불가능했다(docs/plan/point-ledger.md §6 ③).
     */
    @Test
    @DisplayName("포인트 tender 하나로 전액 결제 — PG 호출 없이 원장에서 전액 차감된다")
    void createWithTenders_pointOnly() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        PaymentDomain result = service.createWithTenders(400L,
                List.of(new TenderRequest(TenderType.POINT, new BigDecimal("12000"))), ACTOR_USER_ID);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(result.getAmount()).isEqualByComparingTo("12000");
        assertThat(result.getTenders()).hasSize(1);
        verify(pgClientPort, never()).authorize(anyLong(), any(), anyString());
        verify(pointTenderPort).use(eq(ACTOR_USER_ID), eq(new BigDecimal("12000")), any());
        verify(updateOrderStatusPort).updateOrderStatus(400L, "PAID");
    }

    @Test
    @DisplayName("기프트카드 tender 하나로 전액 결제")
    void createWithTenders_giftCardOnly() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        PaymentDomain result = service.createWithTenders(401L,
                List.of(new TenderRequest(TenderType.GIFT_CARD, new BigDecimal("30000"))), ACTOR_USER_ID);

        assertThat(result.getAmount()).isEqualByComparingTo("30000");
        verify(giftCardTenderPort).use(eq(ACTOR_USER_ID), eq(new BigDecimal("30000")), any());
        verify(pgClientPort, never()).authorize(anyLong(), any(), anyString());
    }

    /**
     * 사용 상한은 PG 를 부르기 전에 본다. 전액 결제는 비율이 100% 라 상한 정책이 걸리면
     * 여기서 끊겨야 한다 — 승인 뒤에 거절하면 취소 보상이 필요해진다.
     */
    @Test
    @DisplayName("포인트 전액 결제도 사용 상한 검사를 거친다 — 주문 전액이 기준액으로 넘어간다")
    void createWithTenders_pointOnlyChecksUsageLimit() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        service.createWithTenders(402L,
                List.of(new TenderRequest(TenderType.POINT, new BigDecimal("12000"))), ACTOR_USER_ID);

        verify(pointTenderPort).assertWithinUsageLimit(
                eq(new BigDecimal("12000")), eq(new BigDecimal("12000")));
    }

    /**
     * 지불수단이 하나뿐인 결제에 "SPLIT:" 을 붙이면 운영 화면에서 분할결제로 읽힌다.
     * 표시값은 사실이어야 한다.
     */
    @Test
    @DisplayName("paymentMethod 라벨 — 텐더 1 개면 수단명 그대로, 2 개 이상이면 SPLIT: 접두")
    void createWithTenders_paymentMethodLabel() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());
        when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("PGTX-9");

        PaymentDomain single = service.createWithTenders(500L,
                List.of(new TenderRequest(TenderType.POINT, new BigDecimal("1000"))), ACTOR_USER_ID);
        PaymentDomain multi = service.createWithTenders(501L, List.of(
                new TenderRequest(TenderType.CARD, new BigDecimal("9000")),
                new TenderRequest(TenderType.POINT, new BigDecimal("1000"))), ACTOR_USER_ID);

        assertThat(single.getPaymentMethod()).isEqualTo("POINT");
        assertThat(multi.getPaymentMethod()).isEqualTo("SPLIT:CARD");
    }

    @Test
    @DisplayName("POINT tender 는 포인트 원장에서 실제로 차감된다 — 장부 없는 결제 수단을 닫는 지점")
    void createWithTenders_deductsPointLedger() {
        when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("PGTX-1");
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        service.createWithTenders(100L, List.of(
                new TenderRequest(TenderType.CARD, new BigDecimal("35000")),
                new TenderRequest(TenderType.POINT, new BigDecimal("5000"))), ACTOR_USER_ID);

        verify(pointTenderPort).use(eq(ACTOR_USER_ID), eq(new BigDecimal("5000")), any());
    }

    @Test
    @DisplayName("GIFT_CARD 도 원장에서 차감된다 — 내부잔액 텐더 두 종류가 모두 검증을 거친다")
    void createWithTenders_deductsGiftCardLedger() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loadSellerSettlementMetaPort.findByPaymentId(any())).thenReturn(Optional.empty());

        service.createWithTenders(200L, List.of(
                new TenderRequest(TenderType.GIFT_CARD, new BigDecimal("3000")),
                new TenderRequest(TenderType.POINT, new BigDecimal("2000"))), ACTOR_USER_ID);

        verify(pointTenderPort, times(1)).use(eq(ACTOR_USER_ID), eq(new BigDecimal("2000")), any());
        verify(giftCardTenderPort, times(1)).use(eq(ACTOR_USER_ID), eq(new BigDecimal("3000")), any());
    }

    @Test
    @DisplayName("인증 주체 없이 포인트로 결제할 수 없다 — 주체를 모른 채 남의 잔액을 건드리지 않는다")
    void createWithTenders_pointRequiresActor() {
        when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.createWithTenders(300L, List.of(
                new TenderRequest(TenderType.POINT, new BigDecimal("1000")),
                new TenderRequest(TenderType.GIFT_CARD, new BigDecimal("1000"))), null))
                .isInstanceOf(PaymentInvariantViolationException.class);
        verify(pointTenderPort, never()).use(any(), any(), any());
    }

    @Test
    @DisplayName("tender 가 없으면 예외 — 금액을 계산할 근거가 없다")
    void createWithTenders_empty() {
        assertThatThrownBy(() -> service.createWithTenders(1L, List.of(), ACTOR_USER_ID))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    @Test
    @DisplayName("tender null 이면 예외")
    void createWithTenders_null() {
        assertThatThrownBy(() -> service.createWithTenders(1L, null, ACTOR_USER_ID))
                .isInstanceOf(PaymentInvariantViolationException.class);
    }

    /**
     * 가상계좌가 섞이면 <b>돈이 아직 안 들어온</b> 결제다. 예전에는 이 결제도 그 자리에서 캡처해
     * 주문을 PAID 로 올리고 payment.captured 를 발행했다 — 입금되지 않은 주문이 정산 대상으로
     * 넘어간다는 뜻이다. 포인트도 즉시 차감돼, 미입금 취소 때 되돌릴 경로가 없었다.
     */
    @Nested
    @DisplayName("입금 대기 결제")
    class AwaitingDeposit {

        @Test
        @DisplayName("가상계좌가 섞이면 캡처하지 않는다 — 주문도 PAID 로 올리지 않고 이벤트도 없다")
        void staysPendingUntilDeposit() {
            when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("VA-1");
            when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));

            PaymentDomain result = service.createWithTenders(600L, List.of(
                    new TenderRequest(TenderType.VIRTUAL_ACCOUNT, new BigDecimal("9000")),
                    new TenderRequest(TenderType.POINT, new BigDecimal("1000"))), ACTOR_USER_ID);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
            verify(pgClientPort).authorize(eq(600L), any(), eq("VIRTUAL_ACCOUNT"));
            verify(pgClientPort, never()).capture(anyString(), any());
            verify(updateOrderStatusPort, never()).updateOrderStatus(anyLong(), anyString());
            verify(publishEventPort, never())
                    .publishPaymentCaptured(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("포인트는 차감이 아니라 선점된다 — 입금 전에는 총액이 줄지 않는다")
        void pointIsHeldNotUsed() {
            when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("VA-1");
            when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));

            service.createWithTenders(601L, List.of(
                    new TenderRequest(TenderType.VIRTUAL_ACCOUNT, new BigDecimal("9000")),
                    new TenderRequest(TenderType.POINT, new BigDecimal("1000"))), ACTOR_USER_ID);

            verify(pointTenderPort).hold(eq(ACTOR_USER_ID), eq(new BigDecimal("1000")), any());
            verify(pointTenderPort, never()).use(any(), any(), any());
        }

        /**
         * 기프트카드도 선점된다(Phase 2). 예전에는 선점 수단이 없어 입금 대기 결제에서 아예
         * 거절했는데, 그러면 상품권을 가진 고객이 가상계좌로 결제할 수 없었다.
         */
        @Test
        @DisplayName("기프트카드도 차감이 아니라 선점된다 — 입금 전에는 카드 잔액이 줄지 않는다")
        void giftCardIsHeldWhileAwaitingDeposit() {
            when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("VA-1");
            when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));

            service.createWithTenders(602L, List.of(
                    new TenderRequest(TenderType.VIRTUAL_ACCOUNT, new BigDecimal("9000")),
                    new TenderRequest(TenderType.GIFT_CARD, new BigDecimal("1000"))), ACTOR_USER_ID);

            verify(giftCardTenderPort).hold(eq(ACTOR_USER_ID), eq(new BigDecimal("1000")), any());
            verify(giftCardTenderPort, never()).use(any(), any(), any());
        }

        /** 상한 검사는 PG 를 부르기 전에 끝나야 한다 — 승인 뒤에 거절하면 취소 보상이 필요해진다. */
        @Test
        @DisplayName("입금 대기 결제도 사용 상한 검사를 먼저 거친다")
        void checksUsageLimitFirst() {
            when(pgClientPort.authorize(anyLong(), any(), anyString())).thenReturn("VA-1");
            when(savePaymentPort.save(any())).thenAnswer(i -> i.getArgument(0));

            service.createWithTenders(603L, List.of(
                    new TenderRequest(TenderType.VIRTUAL_ACCOUNT, new BigDecimal("9000")),
                    new TenderRequest(TenderType.POINT, new BigDecimal("1000"))), ACTOR_USER_ID);

            verify(pointTenderPort).assertWithinUsageLimit(
                    eq(new BigDecimal("10000")), eq(new BigDecimal("1000")));
        }
    }
}
