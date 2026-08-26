package github.lms.lemuel.order.domain;

import github.lms.lemuel.order.domain.exception.InvalidReturnRequestStateException;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OrderReturnRequest — 반품·교환·취소 신청")
class OrderReturnRequestTest {

    private static OrderReturnRequest open(ReturnRequestType type) {
        return OrderReturnRequest.open(1L, 2L, type, "CHANGE_MIND", "색이 달라요",
                null, false, "buyer");
    }

    private static OrderReturnRequest openWithAccount() {
        return OrderReturnRequest.open(1L, 2L, ReturnRequestType.RETURN, "DEFECT", null,
                new RefundAccount("088", "110-123-456789", "홍길동"), true, "buyer");
    }

    @Nested
    @DisplayName("접수")
    class Opening {

        @Test
        @DisplayName("계좌 환불 대상인데 계좌가 없으면 접수되지 않는다")
        void bankRefundNeedsAccount() {
            assertThatThrownBy(() -> OrderReturnRequest.open(1L, 2L, ReturnRequestType.RETURN,
                    "DEFECT", null, null, true, "buyer"))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("계좌");
        }

        @Test
        @DisplayName("교환은 돈이 돌아가지 않으므로 계좌를 받지 않는다")
        void exchangeRejectsAccount() {
            assertThatThrownBy(() -> OrderReturnRequest.open(1L, 2L, ReturnRequestType.EXCHANGE,
                    "DEFECT", null, new RefundAccount("088", "110123456789", "홍길동"), false, "buyer"))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test
        @DisplayName("카드 결제 반품은 계좌 없이 접수된다")
        void cardRefundNeedsNoAccount() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);

            assertThat(request.getStatus()).isEqualTo(ReturnRequestStatus.REQUESTED);
            assertThat(request.getRefundAccount()).isNull();
            // 카드는 계좌가 없는 것이 정상이라 경고 대상이 아니다
            assertThat(request.awaitsRefundAccount()).isFalse();
            assertThat(request.isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("회수")
    class Collecting {

        @Test
        @DisplayName("회수 송장 없이는 회수 완료로 넘길 수 없다")
        void collectRequiresWaybill() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);
            request.approve("admin");

            assertThatThrownBy(() -> request.markCollected("admin"))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("송장");
        }

        @Test
        @DisplayName("승인 전에도 송장은 미리 붙일 수 있다")
        void waybillBeforeApproval() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);
            request.registerReturnWaybill(new ReturnWaybill("CJ", "123456789012"));
            request.approve("admin");
            request.markCollected("admin");

            assertThat(request.getStatus()).isEqualTo(ReturnRequestStatus.COLLECTED);
            assertThat(request.getCollectedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("교환")
    class Exchanging {

        @Test
        @DisplayName("회수되기 전에는 교환품을 보내지 않는다")
        void exchangeShipmentNeedsCollection() {
            OrderReturnRequest request = open(ReturnRequestType.EXCHANGE);
            request.approve("admin");

            assertThatThrownBy(() -> request.shipExchange(new ReturnWaybill("CJ", "999"), "admin"))
                    .isInstanceOf(InvalidReturnRequestStateException.class);
        }

        @Test
        @DisplayName("재배송이 곧 완료다")
        void exchangeShipmentCompletes() {
            OrderReturnRequest request = open(ReturnRequestType.EXCHANGE);
            request.registerReturnWaybill(new ReturnWaybill("CJ", "111"));
            request.approve("admin");
            request.markCollected("admin");
            request.shipExchange(new ReturnWaybill("CJ", "222"), "admin");

            assertThat(request.getStatus()).isEqualTo(ReturnRequestStatus.COMPLETED);
            assertThat(request.getExchangeWaybill().trackingNumber()).isEqualTo("222");
            assertThat(request.getExchangeShippedAt()).isNotNull();
            assertThat(request.isOpen()).isFalse();
        }

        @Test
        @DisplayName("반품 신청은 교환 재배송 경로를 쓸 수 없다")
        void returnCannotShipExchange() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);
            request.registerReturnWaybill(new ReturnWaybill("CJ", "111"));
            request.approve("admin");
            request.markCollected("admin");

            assertThatThrownBy(() -> request.shipExchange(new ReturnWaybill("CJ", "222"), "admin"))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("종단 상태")
    class Terminal {

        @Test
        @DisplayName("거절된 신청은 다시 승인되지 않는다")
        void rejectedIsFinal() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);
            request.reject("admin", "사용 흔적");

            assertThat(request.getStatus()).isEqualTo(ReturnRequestStatus.REJECTED);
            assertThatThrownBy(() -> request.approve("admin"))
                    .isInstanceOf(InvalidReturnRequestStateException.class);
        }

        @Test
        @DisplayName("철회한 신청에는 송장을 붙일 수 없다")
        void withdrawnTakesNoWaybill() {
            OrderReturnRequest request = open(ReturnRequestType.RETURN);
            request.withdraw("buyer");

            assertThatThrownBy(() -> request.registerReturnWaybill(new ReturnWaybill("CJ", "1")))
                    .isInstanceOf(InvalidReturnRequestStateException.class);
        }
    }

    @Nested
    @DisplayName("환불 계좌")
    class Account {

        @Test
        @DisplayName("계좌 번호는 마스킹되어 나간다")
        void masked() {
            OrderReturnRequest request = openWithAccount();

            assertThat(request.getRefundAccount().maskedAccountNumber())
                    .endsWith("6789")
                    .doesNotContain("110123");
        }

        @Test
        @DisplayName("공백·하이픈은 지워 저장한다 — 같은 계좌가 표기만 달라 다른 계좌가 되지 않게")
        void normalized() {
            assertThat(new RefundAccount("088", "110-123 456789", "홍길동").accountNumber())
                    .isEqualTo("110123456789");
        }

        @Test
        @DisplayName("반쪽 계좌는 '계좌를 안 낸 것'이 아니라 오류다")
        void partialAccountFails() {
            assertThatThrownBy(() -> RefundAccount.ofNullable("088", "", "홍길동"))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test
        @DisplayName("무통장 신청은 계좌가 빌 때만 경고한다")
        void awaitsOnlyWhenRequired() {
            assertThat(openWithAccount().awaitsRefundAccount()).isFalse();
            assertThat(OrderReturnRequest.restore(1L, 1L, 2L, ReturnRequestType.RETURN,
                    ReturnRequestStatus.REQUESTED, "DEFECT", null, null, null, null,
                    "buyer", null, null,
                    java.time.LocalDateTime.now(), null, null, null, null,
                    java.time.LocalDateTime.now(), true).awaitsRefundAccount()).isTrue();
        }

        @Test
        @DisplayName("세 칸이 모두 비면 계좌를 내지 않은 것이다")
        void allBlankIsNoAccount() {
            assertThat(RefundAccount.ofNullable(null, "  ", null)).isNull();
        }
    }
}
