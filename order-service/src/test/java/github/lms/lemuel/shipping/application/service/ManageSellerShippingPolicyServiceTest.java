package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.out.LoadSellerShippingPolicyPort;
import github.lms.lemuel.shipping.application.port.out.SaveSellerShippingPolicyPort;
import github.lms.lemuel.shipping.domain.SellerShippingPolicy;
import github.lms.lemuel.shipping.domain.exception.ShipmentInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ManageSellerShippingPolicyService — 배송비 정책 운영")
class ManageSellerShippingPolicyServiceTest {

    private LoadSellerShippingPolicyPort loadPort;
    private SaveSellerShippingPolicyPort savePort;
    private github.lms.lemuel.shipping.application.port.out.SellerExistsPort sellerExistsPort;
    private ManageSellerShippingPolicyService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadSellerShippingPolicyPort.class);
        savePort = mock(SaveSellerShippingPolicyPort.class);
        sellerExistsPort = mock(github.lms.lemuel.shipping.application.port.out.SellerExistsPort.class);
        when(sellerExistsPort.existsById(any())).thenReturn(true);
        service = new ManageSellerShippingPolicyService(loadPort, savePort, sellerExistsPort);
    }

    @Test
    @DisplayName("정책을 저장한다 — 셀러당 1 건 upsert")
    void upsertSaves() {
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SellerShippingPolicy saved = service.upsert(7L, new BigDecimal("3000"), new BigDecimal("50000"));

        assertThat(saved.getSellerId()).isEqualTo(7L);
        assertThat(saved.baseFeeFor(new BigDecimal("50000"))).isEqualByComparingTo("0");
        verify(savePort).save(any());
    }

    @Test
    @DisplayName("임계 없이 저장하면 금액과 무관하게 항상 부과된다")
    void upsertWithoutThreshold() {
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SellerShippingPolicy saved = service.upsert(7L, new BigDecimal("3000"), null);

        assertThat(saved.getFreeThreshold()).isNull();
        assertThat(saved.baseFeeFor(new BigDecimal("9999999"))).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("음수 배송비는 도메인이 거절하고 저장까지 가지 않는다")
    void negativeBaseFeeNeverReachesSave() {
        assertThatThrownBy(() -> service.upsert(7L, new BigDecimal("-1"), null))
                .isInstanceOf(ShipmentInvariantViolationException.class);

        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("조회는 포트에 위임하고 없으면 빈 값")
    void findDelegates() {
        when(loadPort.loadBySellerId(7L)).thenReturn(Optional.empty());
        assertThat(service.find(7L)).isEmpty();

        when(loadPort.loadBySellerId(8L)).thenReturn(
                Optional.of(SellerShippingPolicy.rehydrate(8L, new BigDecimal("2500"), null)));
        assertThat(service.find(8L)).isPresent();
    }

    @Test
    @DisplayName("전체 목록은 포트에 위임한다 — 운영자가 '어느 셀러에 정책이 걸렸는지'를 보는 유일한 경로")
    void findAllDelegates() {
        when(loadPort.loadAll()).thenReturn(java.util.List.of(
                SellerShippingPolicy.rehydrate(7L, new BigDecimal("3000"), new BigDecimal("50000")),
                SellerShippingPolicy.rehydrate(8L, new BigDecimal("2500"), null)));

        assertThat(service.findAll())
                .extracting(SellerShippingPolicy::getSellerId)
                .containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("없는 셀러면 404 로 거절한다 — FK 위반을 500 으로 흘리지 않는다")
    void unknownSellerIsRejectedBeforeSave() {
        when(sellerExistsPort.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.upsert(999L, new BigDecimal("3000"), null))
                .isInstanceOf(github.lms.lemuel.shipping.domain.exception.SellerNotFoundException.class)
                .hasMessageContaining("999");

        // 저장까지 갔다면 DB FK 가 DataIntegrityViolationException → 500 으로 올려보냈을 것이다.
        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("정책이 하나도 없으면 빈 목록 — 화면이 null 을 만나지 않는다")
    void findAllEmpty() {
        when(loadPort.loadAll()).thenReturn(java.util.List.of());
        assertThat(service.findAll()).isEmpty();
    }
}
