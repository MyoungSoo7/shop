package github.lms.lemuel.payment.adapter.out.external;

import github.lms.lemuel.payment.adapter.out.pg.PaymentGatewayAdapter;
import github.lms.lemuel.payment.adapter.out.pg.PgRouter;
import github.lms.lemuel.payment.domain.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingPgClientAdapterTest {

    @Mock PgRouter router;
    @Mock PaymentGatewayAdapter gatewayAdapter;

    private RoutingPgClientAdapter adapter() {
        return new RoutingPgClientAdapter(router);
    }

    @Test
    @DisplayName("authorize: PgRouter 가 선택한 어댑터로 위임하고 결과를 반환")
    void authorize_delegatesToSelectedAdapter() {
        when(router.selectFor(new BigDecimal("10000"), "CARD")).thenReturn(gatewayAdapter);
        when(gatewayAdapter.provider()).thenReturn(PaymentGateway.TOSS);
        when(gatewayAdapter.authorize(1L, new BigDecimal("10000"), "CARD")).thenReturn("TOSS:tx-1");

        String txnId = adapter().authorize(1L, new BigDecimal("10000"), "CARD");

        assertThat(txnId).isEqualTo("TOSS:tx-1");
        verify(gatewayAdapter).authorize(1L, new BigDecimal("10000"), "CARD");
    }

    @Test
    @DisplayName("capture: 거래ID prefix 로 해석한 어댑터에 위임")
    void capture_delegatesToResolvedAdapter() {
        when(router.resolveByTransactionId("TOSS:tx-1")).thenReturn(gatewayAdapter);

        adapter().capture("TOSS:tx-1", new BigDecimal("10000"));

        verify(gatewayAdapter).capture("TOSS:tx-1", new BigDecimal("10000"));
    }

    @Test
    @DisplayName("refund: 거래ID prefix 로 해석한 어댑터에 위임")
    void refund_delegatesToResolvedAdapter() {
        when(router.resolveByTransactionId("KCP:tx-2")).thenReturn(gatewayAdapter);

        adapter().refund("KCP:tx-2", new BigDecimal("5000"), "idem-key");

        verify(gatewayAdapter).refund("KCP:tx-2", new BigDecimal("5000"), "idem-key");
    }
}
