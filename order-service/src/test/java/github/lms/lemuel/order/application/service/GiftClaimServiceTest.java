package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.ClaimGiftUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.SendGiftUseCase;
import github.lms.lemuel.order.application.port.out.CreateShipmentPort;
import github.lms.lemuel.order.application.port.out.LoadGiftClaimPort;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.SaveGiftClaimPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendGiftMessagePort;
import github.lms.lemuel.order.domain.GiftClaim;
import github.lms.lemuel.order.domain.GiftClaimStatus;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.OrderItem;
import github.lms.lemuel.order.domain.ShippingAddressSnapshot;
import github.lms.lemuel.order.domain.exception.GiftClaimNotFoundException;
import github.lms.lemuel.order.domain.exception.InvalidGiftClaimStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GiftClaimService — 선물 보내기·받기")
class GiftClaimServiceTest {

    private static final Long ORDER_ID = 100L;
    private static final Long SENDER_ID = 7L;
    private static final String TOKEN = "plain-token";

    private IdempotentMultiItemOrderUseCase createOrderUseCase;
    private LoadOrderPort loadOrderPort;
    private SaveOrderPort saveOrderPort;
    private CreateShipmentPort createShipmentPort;
    private SaveGiftClaimPort saveGiftClaimPort;
    private LoadGiftClaimPort loadGiftClaimPort;
    private SendGiftMessagePort sendGiftMessagePort;
    private GiftClaimService service;

    @BeforeEach
    void setUp() {
        createOrderUseCase = mock(IdempotentMultiItemOrderUseCase.class);
        loadOrderPort = mock(LoadOrderPort.class);
        saveOrderPort = mock(SaveOrderPort.class);
        createShipmentPort = mock(CreateShipmentPort.class);
        saveGiftClaimPort = mock(SaveGiftClaimPort.class);
        loadGiftClaimPort = mock(LoadGiftClaimPort.class);
        sendGiftMessagePort = mock(SendGiftMessagePort.class);
        service = new GiftClaimService(createOrderUseCase, loadOrderPort, saveOrderPort,
                createShipmentPort, saveGiftClaimPort, loadGiftClaimPort, sendGiftMessagePort,
                "http://localhost:3000/gift/", 14, 5);

        when(saveGiftClaimPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadGiftClaimPort.findByOrderId(anyLong())).thenReturn(Optional.empty());
    }

    // ───────── 픽스처 ─────────

    private static Order order() {
        Order order = Order.createMultiItem(SENDER_ID, List.of(
                OrderItem.newItem(1L, null, "SKU-1", "머그컵", new BigDecimal("12000"), 2)));
        order.assignId(ORDER_ID);
        return order;
    }

    private static SendGiftUseCase.SendCommand command() {
        return new SendGiftUseCase.SendCommand(SENDER_ID,
                List.of(new CreateMultiItemOrderUseCase.Line(1L, null, 2)),
                null, "김수령", "010-1234-5678", "생일 축하해");
    }

    /** 평문 {@link #TOKEN} 으로 열리는 수령 레코드를 저장소에 심는다. */
    private GiftClaim stored(GiftClaimStatus status) {
        LocalDateTime now = LocalDateTime.now();
        GiftClaim claim = GiftClaim.restore(5L, ORDER_ID, SENDER_ID, "김수령", "010-1234-5678",
                "생일 축하해", GiftSecrets.hashToken(TOKEN), status,
                null, null, 0,
                now.plusDays(14), now, null, null, now);
        when(loadGiftClaimPort.findByTokenHash(GiftSecrets.hashToken(TOKEN)))
                .thenReturn(Optional.of(claim));
        when(loadGiftClaimPort.findByOrderId(ORDER_ID)).thenReturn(Optional.of(claim));
        return claim;
    }

    private static ClaimGiftUseCase.AddressSubmission address(String recipientName) {
        return new ClaimGiftUseCase.AddressSubmission(recipientName, "010-9999-8888",
                "06236", "서울시 강남구", "101동 202호", "부재 시 경비실");
    }

    // ───────── 보내는 사람 ─────────

    @Nested
    @DisplayName("보내기")
    class Sending {

        @BeforeEach
        void stubOrderCreation() {
            when(createOrderUseCase.create(anyLong(), any(), any(), any(), any()))
                    .thenReturn(order());
        }

        @Test
        @DisplayName("주문은 일반 경로로 만들되 배송지 자리에 null 을 넘긴다 — 이 경로의 전부다")
        void createsOrderWithoutAddress() {
            service.send(command(), "key-1");

            ArgumentCaptor<ShippingAddressSnapshot> captor =
                    ArgumentCaptor.forClass(ShippingAddressSnapshot.class);
            verify(createOrderUseCase).create(eq(SENDER_ID), any(), isNull(), captor.capture(), eq("key-1"));
            assertThat(captor.getValue()).isNull();
            // 주소가 없으니 배송도 아직 만들어지지 않는다.
            verify(createShipmentPort, never()).createForOrder(anyLong(), any());
        }

        @Test
        @DisplayName("평문 토큰은 반환값에만 있고, 저장되는 것은 그 해시다")
        void storesOnlyTheHash() {
            SendGiftUseCase.SentGift sent = service.send(command(), null);

            ArgumentCaptor<GiftClaim> captor = ArgumentCaptor.forClass(GiftClaim.class);
            verify(saveGiftClaimPort).save(captor.capture());
            assertThat(sent.claimToken()).isNotBlank();
            assertThat(captor.getValue().getTokenHash())
                    .isEqualTo(GiftSecrets.hashToken(sent.claimToken()))
                    .isNotEqualTo(sent.claimToken());
        }

        @Test
        @DisplayName("링크 URL 은 슬래시가 겹치지 않게 붙는다")
        void buildsClaimUrl() {
            SendGiftUseCase.SentGift sent = service.send(command(), null);

            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(sendGiftMessagePort).sendGiftLink(any(), url.capture());
            assertThat(url.getValue()).startsWith("http://localhost:3000/gift/")
                    .doesNotContain("gift//");
            assertThat(sent.linkDelivered()).isTrue();
        }

        @Test
        @DisplayName("발송이 실패해도 주문은 남고, 대신 linkDelivered=false 로 알린다")
        void deliveryFailureDoesNotKillTheOrder() {
            doThrow(new IllegalStateException("알림톡 연동 없음"))
                    .when(sendGiftMessagePort).sendGiftLink(any(), anyString());

            SendGiftUseCase.SentGift sent = service.send(command(), null);

            // 결제된 주문을 문자 한 통 때문에 무를 수는 없다 — 그러나 조용히 성공한 척도 안 한다.
            assertThat(sent.order().getId()).isEqualTo(ORDER_ID);
            assertThat(sent.linkDelivered()).isFalse();
            assertThat(sent.claimToken()).isNotBlank();
        }

        @Test
        @DisplayName("이미 링크가 있는 주문에는 두 번째 링크를 만들지 않는다")
        void rejectsSecondLinkOnSameOrder() {
            stored(GiftClaimStatus.PENDING);

            assertThatThrownBy(() -> service.send(command(), "key-1"))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("이미 선물 링크");
            verify(saveGiftClaimPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("재발송")
    class Resending {

        @Test
        @DisplayName("새 토큰으로 나간다 — 옛 링크로는 더 이상 열리지 않는다")
        void mintsANewToken() {
            GiftClaim claim = stored(GiftClaimStatus.PENDING);
            String oldHash = claim.getTokenHash();

            assertThat(service.resendLink(ORDER_ID)).isTrue();

            assertThat(claim.getTokenHash()).isNotEqualTo(oldHash);
            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            verify(sendGiftMessagePort).sendGiftLink(any(), url.capture());
            // 새 링크의 토큰이 저장된 해시와 짝이 맞아야 실제로 열린다.
            String issued = url.getValue().substring(url.getValue().lastIndexOf('/') + 1);
            assertThat(GiftSecrets.hashToken(issued)).isEqualTo(claim.getTokenHash());
        }

        @Test
        @DisplayName("발송이 실패해도 토큰은 이미 갈렸다 — false 로 알리고 끝낸다")
        void reportsDeliveryFailure() {
            GiftClaim claim = stored(GiftClaimStatus.PENDING);
            String oldHash = claim.getTokenHash();
            doThrow(new IllegalStateException("발송 실패"))
                    .when(sendGiftMessagePort).sendGiftLink(any(), anyString());

            assertThat(service.resendLink(ORDER_ID)).isFalse();
            assertThat(claim.getTokenHash()).isNotEqualTo(oldHash);
        }

        @Test
        @DisplayName("링크가 없는 주문이면 404")
        void unknownOrder() {
            when(loadGiftClaimPort.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.resendLink(ORDER_ID))
                    .isInstanceOf(GiftClaimNotFoundException.class);
        }
    }

    // ───────── 받는 사람 ─────────

    @Nested
    @DisplayName("링크 열기")
    class Viewing {

        @Test
        @DisplayName("금액은 나가지 않고 번호는 가려진다 — 인가가 토큰 하나에 걸려 있기 때문이다")
        void viewIsMinimal() {
            stored(GiftClaimStatus.PENDING);
            when(loadOrderPort.findById(ORDER_ID)).thenReturn(Optional.of(order()));

            ClaimGiftUseCase.GiftView view = service.view(TOKEN);

            assertThat(view.maskedPhone()).isEqualTo("010-****-5678");
            assertThat(view.actionable()).isTrue();
            assertThat(view.items()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.productName()).isEqualTo("머그컵");
                        assertThat(item.quantity()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("모르는 토큰과 빈 토큰은 같은 404 — 존재 여부를 알려 주지 않는다")
        void unknownToken() {
            when(loadGiftClaimPort.findByTokenHash(anyString())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.view("아무거나"))
                    .isInstanceOf(GiftClaimNotFoundException.class);
            assertThatThrownBy(() -> service.view("  "))
                    .isInstanceOf(GiftClaimNotFoundException.class);
            // 빈 토큰은 저장소까지 가지도 않는다.
            verify(loadGiftClaimPort, never()).findByTokenHash("");
        }
    }

    @Nested
    @DisplayName("본인확인")
    class Verifying {

        /** 서비스가 실제로 발송한 6자리를 가로챈다 — 테스트가 해시 규칙을 다시 쓰지 않게. */
        private String issueCode(GiftClaim claim) {
            service.requestVerificationCode(TOKEN);
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(sendGiftMessagePort).sendVerificationCode(eq(claim), code.capture());
            return code.getValue();
        }

        @Test
        @DisplayName("발송한 번호를 넣으면 통과한다")
        void happyPath() {
            GiftClaim claim = stored(GiftClaimStatus.PENDING);
            String code = issueCode(claim);

            service.verify(TOKEN, code);

            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.VERIFIED);
        }

        @Test
        @DisplayName("6자리이고 앞자리 0 을 허용한다 — 범위를 좁히면 경우의 수가 준다")
        void codeShape() {
            GiftClaim claim = stored(GiftClaimStatus.PENDING);
            assertThat(issueCode(claim)).matches("\\d{6}");
        }

        @Test
        @DisplayName("틀린 번호는 시도 횟수를 올린 채로 저장된다 — 저장하지 않으면 무제한 대입이 된다")
        void failureIsPersisted() {
            GiftClaim claim = stored(GiftClaimStatus.PENDING);
            issueCode(claim);

            assertThatThrownBy(() -> service.verify(TOKEN, "000000"))
                    .isInstanceOf(InvalidGiftClaimStateException.class);

            assertThat(claim.getVerifyAttempts()).isEqualTo(1);
            // 발급 저장 1회 + 실패 저장 1회.
            verify(saveGiftClaimPort, times(2)).save(claim);
        }

        @Test
        @DisplayName("발송 실패는 삼키지 않는다 — 오지 않을 번호를 기다리게 하는 것보다 낫다")
        void codeDeliveryFailurePropagates() {
            stored(GiftClaimStatus.PENDING);
            doThrow(new IllegalStateException("발송 실패"))
                    .when(sendGiftMessagePort).sendVerificationCode(any(), anyString());

            assertThatThrownBy(() -> service.requestVerificationCode(TOKEN))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("배송지 제출")
    class SubmittingAddress {

        @Test
        @DisplayName("주문에 주소가 붙고 배송이 생기고 나서야 수령으로 남는다")
        void attachesThenClaims() {
            GiftClaim claim = stored(GiftClaimStatus.VERIFIED);
            Order order = order();
            when(loadOrderPort.findById(ORDER_ID)).thenReturn(Optional.of(order));

            service.submitAddress(TOKEN, address("회사 프론트"));

            assertThat(order.getShippingAddress().recipientName()).isEqualTo("회사 프론트");
            verify(saveOrderPort).save(order);
            verify(createShipmentPort).createForOrder(eq(ORDER_ID), any());
            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.CLAIMED);
        }

        @Test
        @DisplayName("이름을 비우면 선물에 적힌 이름을 쓴다 — 화면에 이미 적혀 있는 것을 또 받지 않는다")
        void fallsBackToGiftRecipientName() {
            stored(GiftClaimStatus.VERIFIED);
            Order order = order();
            when(loadOrderPort.findById(ORDER_ID)).thenReturn(Optional.of(order));

            service.submitAddress(TOKEN, address("   "));

            assertThat(order.getShippingAddress().recipientName()).isEqualTo("김수령");
        }

        @Test
        @DisplayName("본인확인 전에는 주소를 낼 수 없다 — 링크를 주운 사람이 배송지를 바꾸는 길")
        void requiresVerification() {
            stored(GiftClaimStatus.PENDING);
            when(loadOrderPort.findById(ORDER_ID)).thenReturn(Optional.of(order()));

            assertThatThrownBy(() -> service.submitAddress(TOKEN, address("김수령")))
                    .isInstanceOf(InvalidGiftClaimStateException.class);
        }
    }

    @Nested
    @DisplayName("소멸 배치")
    class Expiring {

        @Test
        @DisplayName("설정이 아무리 커도 한 번에 훑는 양은 상한에서 잘린다")
        void clampsBatchSize() {
            when(loadGiftClaimPort.findExpirable(any(), anyInt())).thenReturn(List.of());

            service.expireOverdue(LocalDateTime.now(), 999_999);

            verify(loadGiftClaimPort).findExpirable(any(), eq(1000));
        }

        @Test
        @DisplayName("0 이하를 줘도 최소 1 건은 훑는다")
        void clampsToAtLeastOne() {
            when(loadGiftClaimPort.findExpirable(any(), anyInt())).thenReturn(List.of());

            service.expireOverdue(LocalDateTime.now(), 0);

            verify(loadGiftClaimPort).findExpirable(any(), eq(1));
        }

        @Test
        @DisplayName("찾은 것들을 EXPIRED 로 남기고 건수를 돌려준다")
        void marksExpired() {
            LocalDateTime now = LocalDateTime.now();
            GiftClaim overdue = GiftClaim.restore(9L, 200L, SENDER_ID, "김수령", "010-1234-5678",
                    null, "hash", GiftClaimStatus.PENDING, null, null, 0,
                    now.minusDays(1), now.minusDays(15), null, null, now.minusDays(15));
            when(loadGiftClaimPort.findExpirable(any(), anyInt())).thenReturn(List.of(overdue));

            assertThat(service.expireOverdue(now, 500)).isEqualTo(1);
            assertThat(overdue.getStatus()).isEqualTo(GiftClaimStatus.EXPIRED);
            verify(saveGiftClaimPort).save(overdue);
        }
    }

    @Nested
    @DisplayName("거둬들이기")
    class Canceling {

        @Test
        @DisplayName("링크만 닫는다 — 주문 취소는 반품·취소 경로의 일이다")
        void cancelsLinkOnly() {
            GiftClaim claim = stored(GiftClaimStatus.PENDING);

            service.cancel(ORDER_ID);

            assertThat(claim.getStatus()).isEqualTo(GiftClaimStatus.CANCELED);
            verify(saveOrderPort, never()).save(any());
        }
    }
}
