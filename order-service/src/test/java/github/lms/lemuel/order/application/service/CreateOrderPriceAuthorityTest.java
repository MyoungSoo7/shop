package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.PublishOrderEventPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.product.application.port.out.LoadProductPort;
import github.lms.lemuel.product.domain.Product;
import github.lms.lemuel.product.domain.exception.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 단건 주문 금액의 권위는 서버에 있다.
 *
 * <p>이 경로는 요청 본문의 {@code amount} 를 그대로 주문 금액으로 썼다 — 100 만원짜리 상품을 1 원에
 * 주문할 수 있었고, 그 금액이 결제·정산·원장까지 그대로 흘렀다. 다건 주문 경로는 이미 상품
 * 마스터에서 단가를 읽고 있었으므로, 두 경로의 권위를 같은 곳으로 맞춘다.
 */
@DisplayName("CreateOrderService — 주문 금액은 상품 마스터가 정한다")
class CreateOrderPriceAuthorityTest {

    private LoadUserForOrderPort loadUserPort;
    private LoadProductPort loadProductPort;
    private SaveOrderPort saveOrderPort;
    private SendOrderNotificationPort notificationPort;
    private PublishOrderEventPort publishPort;
    private CreateOrderService service;

    @BeforeEach
    void setUp() {
        loadUserPort = mock(LoadUserForOrderPort.class);
        loadProductPort = mock(LoadProductPort.class);
        saveOrderPort = mock(SaveOrderPort.class);
        notificationPort = mock(SendOrderNotificationPort.class);
        publishPort = mock(PublishOrderEventPort.class);
        service = new CreateOrderService(loadUserPort, loadProductPort, saveOrderPort,
                notificationPort, publishPort);
        when(loadUserPort.findEmailById(1L)).thenReturn(Optional.of("user@example.com"));
        when(saveOrderPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void product(Long id, String price) {
        Product p = spy(Product.create("상품A", "설명", new BigDecimal(price), 100));
        when(p.getId()).thenReturn(id);
        when(loadProductPort.findById(id)).thenReturn(Optional.of(p));
    }

    @Test
    @DisplayName("요청 금액을 생략하면 상품 가격으로 주문이 만들어진다")
    void amountResolvedFromProduct() {
        product(10L, "50000");

        Order order = service.createOrder(new CreateOrderUseCase.CreateOrderCommand(1L, 10L, null));

        assertThat(order.getAmount()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("요청 금액이 상품 가격과 같으면 통과 — scale 차이는 같은 금액으로 본다")
    void matchingAmountPasses() {
        product(10L, "50000");

        Order order = service.createOrder(
                new CreateOrderUseCase.CreateOrderCommand(1L, 10L, new BigDecimal("50000.00")));

        assertThat(order.getAmount()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("요청 금액이 상품 가격과 다르면 거절 — 위변조도 낡은 가격도 조용히 통과시키지 않는다")
    void tamperedAmountRejected() {
        product(10L, "1000000");

        assertThatThrownBy(() -> service.createOrder(
                new CreateOrderUseCase.CreateOrderCommand(1L, 10L, BigDecimal.ONE)))
                .isInstanceOf(OrderAmountMismatchException.class);

        verify(saveOrderPort, never()).save(any());
        verify(notificationPort, never()).sendOrderConfirmation(anyString(), any());
    }

    @Test
    @DisplayName("없는 상품이면 404 — 사용자 확인보다 뒤, 저장보다 앞에서 끊는다")
    void unknownProductRejected() {
        when(loadProductPort.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(
                new CreateOrderUseCase.CreateOrderCommand(1L, 77L, new BigDecimal("1000"))))
                .isInstanceOf(ProductNotFoundException.class);

        verify(saveOrderPort, never()).save(any());
    }
}
