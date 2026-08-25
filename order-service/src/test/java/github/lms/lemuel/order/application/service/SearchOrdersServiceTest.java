package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase.OrderPage;
import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase.OrderQuery;
import github.lms.lemuel.order.application.port.in.SearchOrdersUseCase.OrderStatusCount;
import github.lms.lemuel.order.application.port.out.SearchOrdersPort;
import github.lms.lemuel.order.application.port.out.SearchOrdersPort.OrderCriteria;
import github.lms.lemuel.order.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 주문 조회 서비스 — 정규화와 상한이 실제로 걸리는지 본다.
 *
 * <p>여기 있는 케이스들은 대부분 <b>조용히 틀리는</b> 종류다. 상한이 안 걸려도 응답은 200 이고,
 * 상태 문자열이 대소문자 때문에 안 맞아도 "그 상태 주문이 없는" 화면과 구분되지 않는다.
 */
class SearchOrdersServiceTest {

    private SearchOrdersPort port;
    private SearchOrdersService service;

    @BeforeEach
    void setUp() {
        port = mock(SearchOrdersPort.class);
        service = new SearchOrdersService(port);
    }

    @Test
    @DisplayName("size 상한 — 무페이징으로 되돌리는 호출을 서버가 막는다")
    void clampsSize() {
        when(port.count(any())).thenReturn(1_000_000L);
        when(port.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        OrderPage page = service.search(new OrderQuery(List.of(), null, null, 0, 1_000_000));

        assertThat(page.size()).isEqualTo(SearchOrdersService.MAX_PAGE_SIZE);
        verify(port).search(any(), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(SearchOrdersService.MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("size 0/음수는 기본값, page 음수는 0")
    void normalizesDefaults() {
        when(port.count(any())).thenReturn(10L);
        when(port.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        OrderPage page = service.search(new OrderQuery(List.of(), null, null, -3, 0));

        assertThat(page.size()).isEqualTo(SearchOrdersService.DEFAULT_PAGE_SIZE);
        assertThat(page.page()).isZero();
    }

    @Test
    @DisplayName("총 건수가 0이면 목록 쿼리를 아예 던지지 않는다")
    void skipsSearchWhenEmpty() {
        when(port.count(any())).thenReturn(0L);

        OrderPage page = service.search(OrderQuery.firstPage(50));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalPages()).isZero();
        verify(port, never()).search(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("totalPages 는 올림 — 51건이 50건 페이지면 2쪽이다")
    void roundsUpTotalPages() {
        when(port.count(any())).thenReturn(51L);
        when(port.search(any(), anyInt(), anyInt())).thenReturn(List.of(mock(Order.class)));

        assertThat(service.search(OrderQuery.firstPage(50)).totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("상태는 대문자로 정규화하고 빈 값·중복은 버린다")
    void normalizesStatuses() {
        when(port.count(any())).thenReturn(0L);

        service.search(new OrderQuery(List.of("paid", "PAID", "  ", "canceled"), null, null, 0, 50));

        ArgumentCaptor<OrderCriteria> captor = ArgumentCaptor.forClass(OrderCriteria.class);
        verify(port).count(captor.capture());
        assertThat(captor.getValue().statuses()).containsExactly("PAID", "CANCELED");
    }

    @Test
    @DisplayName("뒤집힌 기간은 거부하지 않고 바로잡는다 — 에러를 던지면 운영자는 빈 화면만 본다")
    void swapsInvertedRange() {
        when(port.count(any())).thenReturn(0L);
        LocalDateTime later = LocalDateTime.of(2026, 8, 26, 0, 0);
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 1, 0, 0);

        service.search(new OrderQuery(List.of(), later, earlier, 0, 50));

        ArgumentCaptor<OrderCriteria> captor = ArgumentCaptor.forClass(OrderCriteria.class);
        verify(port).count(captor.capture());
        assertThat(captor.getValue().createdFrom()).isEqualTo(earlier);
        assertThat(captor.getValue().createdToExclusive()).isEqualTo(later);
    }

    @Test
    @DisplayName("집계는 같은 조건을 쓰되 페이지에 잘리지 않는다")
    void aggregatesWithSameCriteria() {
        when(port.countByStatus(any())).thenReturn(List.of(
                new OrderStatusCount("PAID", 10L, new BigDecimal("1000.00"))));

        List<OrderStatusCount> counts =
                service.countByStatus(new OrderQuery(List.of("paid"), null, null, 5, 1));

        assertThat(counts).hasSize(1);
        ArgumentCaptor<OrderCriteria> captor = ArgumentCaptor.forClass(OrderCriteria.class);
        verify(port).countByStatus(captor.capture());
        assertThat(captor.getValue().statuses()).containsExactly("PAID");
        // page/size 는 집계에 넘어가지 않는다 — 넘어가면 "전 범위 집계"가 아니게 된다.
        verify(port, never()).search(any(), anyInt(), anyInt());
    }
}
