package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실 연동 어댑터 — <b>예외를 결과 객체로 바꾸는 경계</b>가 이 클래스의 존재 이유다.
 *
 * <p>{@code CashReceiptService} 는 발급 실패를 예외가 아니라 {@code Result.failed} 로 받아야 한다.
 * 예외가 서비스까지 올라가면 트랜잭션이 롤백되어 <b>시도한 흔적(REQUESTED 행)까지 사라진다</b> —
 * 국세청 쪽에서 부분 성공했을 경우를 영영 알 수 없게 된다. 그래서 HTTP 왕복에서 나오는 모든
 * 예외는 여기서 멈춘다.
 */
class LiveCashReceiptGatewayAdapterTest {

    private CashReceiptApiClient apiClient;
    private LiveCashReceiptGatewayAdapter adapter;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);

    @BeforeEach
    void setUp() {
        apiClient = mock(CashReceiptApiClient.class);
        adapter = new LiveCashReceiptGatewayAdapter(apiClient);
    }

    private CashReceipt requested() {
        return CashReceipt.request(77L, 501L, 9L, "BANK_TRANSFER", new BigDecimal("11000"),
                CashReceiptPurpose.INCOME_DEDUCTION,
                CashReceiptIdentifier.of(CashReceiptIdentifier.Type.MOBILE, "010-1234-5678"),
                NOW);
    }

    private CashReceipt issued() {
        CashReceipt receipt = requested();
        receipt.markIssued("NTS-0001", NOW);
        return receipt;
    }

    @Nested
    @DisplayName("발급")
    class Issue {

        @Test
        @DisplayName("승인번호를 받으면 성공 결과로 돌려준다")
        void success() {
            when(apiClient.issue(any())).thenReturn("NTS-77001");

            CashReceiptGatewayPort.Result result = adapter.issue(requested());

            assertThat(result.success()).isTrue();
            assertThat(result.approvalNumber()).isEqualTo("NTS-77001");
            assertThat(result.message()).isNull();
        }

        /**
         * 승인번호 없는 성공은 성공이 아니다 — {@code markIssued} 가 승인번호를 요구하므로 그대로
         * 넘기면 도메인에서 예외가 터져 트랜잭션이 롤백된다(= 시도 흔적 소실).
         */
        @Test
        @DisplayName("승인번호가 비어 있으면 실패로 본다")
        void blankApprovalNumberIsFailure() {
            when(apiClient.issue(any())).thenReturn("   ");

            CashReceiptGatewayPort.Result result = adapter.issue(requested());

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("승인번호");
        }

        @Test
        @DisplayName("연동 오류는 예외로 올리지 않고 실패 결과로 바꾼다")
        void businessErrorBecomesFailedResult() {
            when(apiClient.issue(any()))
                    .thenThrow(new IllegalStateException("현금영수증 발급 실패 (400): 이미 발급된 거래"));

            CashReceiptGatewayPort.Result result = adapter.issue(requested());

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("이미 발급된 거래");
        }

        @Test
        @DisplayName("예상 못한 예외(서킷 오픈·타임아웃)도 실패 결과로 흡수한다")
        void unexpectedErrorBecomesFailedResult() {
            when(apiClient.issue(any())).thenThrow(new RuntimeException("connect timed out"));

            CashReceiptGatewayPort.Result result = adapter.issue(requested());

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("connect timed out");
        }

        /** 실패 사유가 응답·로그를 타고 나가므로 식별번호 원문이 섞여서는 안 된다. */
        @Test
        @DisplayName("실패 사유에 식별번호 원문이 섞이지 않는다")
        void failureMessageDoesNotLeakIdentifier() {
            when(apiClient.issue(any())).thenThrow(new RuntimeException("발급 거부"));

            CashReceiptGatewayPort.Result result = adapter.issue(requested());

            assertThat(result.message()).doesNotContain("01012345678");
        }
    }

    @Nested
    @DisplayName("취소")
    class Cancel {

        @Test
        @DisplayName("정상 취소는 성공 결과")
        void success() {
            doNothing().when(apiClient).cancel(any(), anyString());

            CashReceiptGatewayPort.Result result = adapter.cancel(issued(), "전액 환불");

            assertThat(result.success()).isTrue();
        }

        /**
         * 취소 실패를 성공으로 삼키면 국세청에는 발급이 살아 있는데 우리 장부에는 취소로 남는다.
         * 실패는 실패로 돌려줘야 {@code revertCancel} 이 발급 상태를 되살린다.
         */
        @Test
        @DisplayName("취소 실패는 실패 결과로 돌려준다")
        void failureBecomesFailedResult() {
            doThrow(new IllegalStateException("취소 가능 기간이 지났습니다"))
                    .when(apiClient).cancel(any(), anyString());

            CashReceiptGatewayPort.Result result = adapter.cancel(issued(), "전액 환불");

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("취소 가능 기간");
        }
    }
}
