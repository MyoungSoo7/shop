package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GetOrderServiceTest {

    @Mock LoadOrderPort loadOrderPort;
    @InjectMocks GetOrderService service;

    @Test @DisplayName("ID로 주문 조회 성공")
    void getById_success() {
        Order order = Order.create(1L, 1L, new BigDecimal("5000"));
        when(loadOrderPort.findById(1L)).thenReturn(Optional.of(order));

        Order result = service.getOrderById(1L);
        assertThat(result).isNotNull();
    }

    @Test @DisplayName("ID로 주문 조회 실패")
    void getById_notFound() {
        when(loadOrderPort.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOrderById(999L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test @DisplayName("사용자별 주문 조회")
    void getByUserId() {
        when(loadOrderPort.findByUserId(1L)).thenReturn(List.of());
        List<Order> result = service.getOrdersByUserId(1L);
        assertThat(result).isEmpty();
        verify(loadOrderPort).findByUserId(1L);
    }

    @Test @DisplayName("전체 주문 조회")
    void getAll() {
        when(loadOrderPort.findAll()).thenReturn(List.of());
        List<Order> result = service.getAllOrders();
        assertThat(result).isEmpty();
    }
}
