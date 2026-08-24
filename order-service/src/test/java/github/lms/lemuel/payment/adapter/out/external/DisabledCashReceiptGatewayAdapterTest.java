package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.application.port.out.CashReceiptGatewayPort;
import github.lms.lemuel.payment.domain.CashReceipt;
import github.lms.lemuel.payment.domain.CashReceiptIdentifier;
import github.lms.lemuel.payment.domain.CashReceiptPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 현금영수증 대행 연동이 구성되지 않은 운영 환경의 자리 채우기.
 *
 * <p>모의 어댑터와의 차이가 이 클래스의 전부다 — 모의는 <b>성공</b>을 돌려주고, 이쪽은
 * <b>실패</b>를 돌려준다. 발급되지 않은 것을 발급됐다고 기록하지 않는 쪽이, 발급 실패가
 * 눈에 보이는 쪽보다 언제나 위험하다.
 */
class DisabledCashReceiptGatewayAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);

    private final DisabledCashReceiptGatewayAdapter adapter = new DisabledCashReceiptGatewayAdapter();

    private CashReceipt receipt() {
        return CashReceipt.request(77L, 501L, 9L, "VIRTUAL_ACCOUNT", new BigDecimal("11000"),
                CashReceiptPurpose.EXPENSE_PROOF,
                CashReceiptIdentifier.of(CashReceiptIdentifier.Type.BUSINESS_NUMBER, "2208162517"),
                NOW);
    }

    @Test
    @DisplayName("발급은 언제나 실패 — 성공을 흉내내지 않는다")
    void issueAlwaysFails() {
        CashReceiptGatewayPort.Result result = adapter.issue(receipt());

        assertThat(result.success()).isFalse();
        assertThat(result.approvalNumber()).isNull();
        assertThat(result.message()).contains("현금영수증");
    }

    @Test
    @DisplayName("취소도 실패 — 발급된 적이 없으므로 취소됐다고 기록해서는 안 된다")
    void cancelAlwaysFails() {
        CashReceiptGatewayPort.Result result = adapter.cancel(receipt(), "환불");

        assertThat(result.success()).isFalse();
    }
}
