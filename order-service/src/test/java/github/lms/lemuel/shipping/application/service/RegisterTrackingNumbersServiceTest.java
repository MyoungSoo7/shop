package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase.BulkTrackingResult;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.domain.Shipment;
import github.lms.lemuel.shipping.domain.ShippingAddress;
import github.lms.lemuel.shipping.domain.TrackingNumberRegistration;
import github.lms.lemuel.shipping.domain.exception.InvalidShipmentStateException;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 송장 일괄 등록.
 *
 * <p>수백 행이 한 번에 들어오므로 <b>실행 전 미리보기가 기본</b>이고, 한 행의 실패가 나머지를
 * 막지 않는다. 실패한 행은 사유와 함께 그대로 돌려줘 운영자가 그 행만 고쳐 다시 올릴 수 있게 한다.
 */
@ExtendWith(MockitoExtension.class)
class RegisterTrackingNumbersServiceTest {

    @Mock ShippingUseCase shippingUseCase;
    @InjectMocks RegisterTrackingNumbersService service;

    private Shipment shipment(Long orderId) {
        return Shipment.createPending(orderId,
                new ShippingAddress("받는이", "010-0000-0000", "12345", "서울시", "101호", null));
    }

    @Test @DisplayName("dryRun 은 아무 것도 출고시키지 않고 예상 결과만 돌려준다")
    void dryRunChangesNothing() {
        List<TrackingNumberRegistration> rows = List.of(
                TrackingNumberRegistration.of(7L, "CJ", "111"));

        BulkTrackingResult result = service.register(rows, true);

        verifyNoInteractions(shippingUseCase);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.applied()).isEqualTo(1);   // "적용될 것" 예고
        assertThat(result.failed()).isZero();
    }

    @Test @DisplayName("유효한 행은 출고 처리한다")
    void appliesValidRows() {
        when(shippingUseCase.ship(eq(7L), anyString(), anyString())).thenReturn(shipment(7L));

        BulkTrackingResult result = service.register(
                List.of(TrackingNumberRegistration.of(7L, "CJ", "111")), false);

        verify(shippingUseCase).ship(7L, "CJ", "111");
        assertThat(result.applied()).isEqualTo(1);
    }

    @Test @DisplayName("도메인이 이미 거절한 행은 출고를 시도하지 않는다")
    void skipsInvalidRows() {
        BulkTrackingResult result = service.register(
                List.of(TrackingNumberRegistration.of(null, "CJ", "111")), false);

        verify(shippingUseCase, never()).ship(anyLong(), anyString(), anyString());
        assertThat(result.applied()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.lines().get(0).reason()).contains("주문");
    }

    @Test @DisplayName("한 행이 실패해도 나머지는 계속 처리한다 — 파일 전체를 되돌리지 않는다")
    void oneFailureDoesNotStopBatch() {
        when(shippingUseCase.ship(eq(7L), anyString(), anyString()))
                .thenThrow(new InvalidShipmentStateException(
                        github.lms.lemuel.shipping.domain.ShippingStatus.SHIPPED,
                        github.lms.lemuel.shipping.domain.ShippingStatus.SHIPPED));
        when(shippingUseCase.ship(eq(8L), anyString(), anyString())).thenReturn(shipment(8L));

        BulkTrackingResult result = service.register(List.of(
                TrackingNumberRegistration.of(7L, "CJ", "111"),
                TrackingNumberRegistration.of(8L, "CJ", "222")), false);

        verify(shippingUseCase).ship(8L, "CJ", "222");
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test @DisplayName("실패 사유는 그 행에 담아 돌려준다 — 무엇을 고칠지 알 수 있게")
    void failureReasonIsReported() {
        doThrow(new ShipmentInvariantViolationException("배송 없음: orderId=7"))
                .when(shippingUseCase).ship(eq(7L), anyString(), anyString());

        BulkTrackingResult result = service.register(
                List.of(TrackingNumberRegistration.of(7L, "CJ", "111")), false);

        assertThat(result.lines().get(0).reason()).contains("배송 없음");
        assertThat(result.lines().get(0).applied()).isFalse();
    }

    @Test @DisplayName("dryRun 은 도메인이 거절한 행도 미리 알려준다")
    void dryRunReportsInvalidRows() {
        BulkTrackingResult result = service.register(List.of(
                TrackingNumberRegistration.of(7L, "CJ", "111"),
                TrackingNumberRegistration.of(8L, "CJ", "")), true);

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test @DisplayName("빈 파일은 0건으로 처리한다")
    void emptyInput() {
        BulkTrackingResult result = service.register(List.of(), false);

        assertThat(result.applied()).isZero();
        assertThat(result.lines()).isEmpty();
    }
}
