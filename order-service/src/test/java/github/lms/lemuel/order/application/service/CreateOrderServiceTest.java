package github.lms.lemuel.order.application.service;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;

import github.lms.lemuel.order.application.port.in.CreateOrderUseCase;
import github.lms.lemuel.order.application.port.out.LoadUserForOrderPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPort;
import github.lms.lemuel.order.application.port.out.SendOrderNotificationPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.UserNotExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateOrderServiceTest {

    @Mock LoadUserForOrderPort loadUserForOrderPort;
    @Mock github.lms.lemuel.product.application.port.out.LoadProductPort loadProductPort;
    @Mock SaveOrderPort saveOrderPort;
    @Mock SendOrderNotificationPort sendOrderNotificationPort;
    @Mock github.lms.lemuel.order.application.port.out.PublishOrderEventPort publishOrderEventPort;
    @InjectMocks CreateOrderService service;

    @Test @DisplayName("정상 주문 생성 + 알림 발송")
    void createOrder_success() {
        when(loadUserForOrderPort.findEmailById(1L)).thenReturn(Optional.of("user@example.com"));
        when(loadProductPort.findById(10L)).thenReturn(Optional.of(
                github.lms.lemuel.product.domain.Product.create("상품A", "설명", new BigDecimal("50000"), 100)));
        when(saveOrderPort.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.assignId(999L);
            return o;
        });

        Order result = service.createOrder(new CreateOrderUseCase.CreateOrderCommand(
                1L, 10L, new BigDecimal("50000")));

        assertThat(result.getId()).isEqualTo(999L);
        verify(sendOrderNotificationPort).sendOrderConfirmation(eq("user@example.com"), any());
    }

    @Test @DisplayName("사용자 미존재 시 UserNotExistsException + 주문 저장·알림 없음")
    void createOrder_userNotExists() {
        when(loadUserForOrderPort.findEmailById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(new CreateOrderUseCase.CreateOrderCommand(
                999L, 10L, new BigDecimal("50000"))))
                .isInstanceOf(UserNotExistsException.class);
        verify(saveOrderPort, never()).save(any());
        verify(sendOrderNotificationPort, never()).sendOrderConfirmation(anyString(), any());
    }

    @Test @DisplayName("Command 검증: userId null")
    void command_nullUserId() {
        assertThatThrownBy(() -> new CreateOrderUseCase.CreateOrderCommand(
                null, 10L, new BigDecimal("1000")))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test @DisplayName("Command 검증: productId null")
    void command_nullProductId() {
        assertThatThrownBy(() -> new CreateOrderUseCase.CreateOrderCommand(
                1L, null, new BigDecimal("1000")))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    @Test @DisplayName("Command 검증: amount 0 이하")
    void command_nullAmount() {
        assertThatThrownBy(() -> new CreateOrderUseCase.CreateOrderCommand(
                1L, 10L, BigDecimal.ZERO))
                .isInstanceOf(OrderInvariantViolationException.class);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
