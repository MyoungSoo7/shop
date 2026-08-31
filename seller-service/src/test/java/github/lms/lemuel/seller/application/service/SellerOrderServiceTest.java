package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SellerOrderPage;
import github.lms.lemuel.seller.application.port.dto.SellerOrderQuery;
import github.lms.lemuel.seller.application.port.dto.SellerOrderView;
import github.lms.lemuel.seller.application.port.out.SellerOrderQueryPort;
import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.exception.NotASellerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 내 상품이 주문된 것 — 목록·상세.
 *
 * <p>여기서 고정하는 것은 ① 두 경로가 같은 포트를 쓰고 둘 다 스코프의 셀러로만 조회한다,
 * ② 상세는 기본 기간 창을 적용하지 않는다, 이 둘이다. 두 번째가 중요한 이유는 송장 등록 화면이
 * 목록 밖(메일·메모)에서도 열려서다 — 기간을 걸면 "목록에는 있는데 상세는 없다" 가 된다.
 */
class SellerOrderServiceTest {

    /** KST 로 2026-09-01 10:00. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-01T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private SellerOrderQueryPort orderQueryPort;
    private SellerOrderService service;

    @BeforeEach
    void setUp() {
        orderQueryPort = mock(SellerOrderQueryPort.class);
        service = new SellerOrderService(orderQueryPort, FIXED);
    }

    private static SellerScope seller() {
        return new SellerScope(7L, "명수상사", OrgType.SELLER, 777L, MemberRole.STAFF);
    }

    private static SellerOrderView view() {
        return new SellerOrderView(100L, 200L, LocalDateTime.of(2026, 8, 30, 14, 0), false,
                new BigDecimal("12900"), BigDecimal.ZERO, new BigDecimal("12900"), "CARD",
                "PAID", 5001L, "사과 1kg", false, null, null, null);
    }

    @Test
    void 기간을_비우면_오늘까지_최근_30일을_본다() {
        LocalDate to = LocalDate.of(2026, 9, 1);
        LocalDate from = to.minusDays(SellerOrderQuery.DEFAULT_DAYS - 1L);
        when(orderQueryPort.countOrders(777L, from, to, null, false)).thenReturn(1L);
        when(orderQueryPort.findOrders(777L, from, to, null, false, 20, 0L)).thenReturn(List.of(view()));

        SellerOrderPage page = service.orders(seller(), new SellerOrderQuery(null, null, null, false, 0, 20));

        assertEquals(1, page.content().size());
        // 오늘은 주입된 시계에서 온다. 시스템 시계를 쓰면 자정 언저리에 테스트가 흔들린다.
        verify(orderQueryPort).findOrders(777L, from, to, null, false, 20, 0L);
    }

    @Test
    void 총건수가_0이면_두번째_쿼리를_쏘지_않는다() {
        when(orderQueryPort.countOrders(anyLong(), any(), any(), any(), anyBoolean())).thenReturn(0L);

        SellerOrderPage page = service.orders(seller(), new SellerOrderQuery(null, null, null, false, 0, 20));

        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalPages());
        verify(orderQueryPort, never()).findOrders(anyLong(), any(), any(), any(), anyBoolean(), anyInt(), anyLong());
    }

    @Test
    void 페이지와_크기는_정규화된_값으로_오프셋이_된다() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        when(orderQueryPort.countOrders(777L, from, to, null, true)).thenReturn(45L);
        when(orderQueryPort.findOrders(777L, from, to, null, true, 20, 40L)).thenReturn(List.of(view()));

        SellerOrderPage page = service.orders(seller(), new SellerOrderQuery(from, to, null, true, 2, 0));

        assertEquals(2, page.page());
        assertEquals(20, page.size());
        assertEquals(45L, page.totalElements());
        assertEquals(3, page.totalPages());
    }

    @Test
    void 페이지_크기는_상한을_넘지_못한다() {
        LocalDate to = LocalDate.of(2026, 9, 1);
        LocalDate from = to.minusDays(SellerOrderQuery.DEFAULT_DAYS - 1L);
        when(orderQueryPort.countOrders(777L, from, to, null, false)).thenReturn(1000L);
        when(orderQueryPort.findOrders(777L, from, to, null, false, SellerOrderQuery.MAX_SIZE, 0L))
                .thenReturn(List.of());

        SellerOrderPage page = service.orders(seller(), new SellerOrderQuery(null, null, null, false, -1, 9999));

        assertEquals(0, page.page());
        assertEquals(SellerOrderQuery.MAX_SIZE, page.size());
    }

    @Test
    void 상세는_기간을_열어_두고_주문번호로만_찾는다() {
        when(orderQueryPort.findOrders(777L, LocalDate.EPOCH, LocalDate.of(2026, 9, 2),
                100L, false, 1, 0L)).thenReturn(List.of(view()));

        Optional<SellerOrderView> found = service.order(seller(), 100L);

        assertTrue(found.isPresent());
        assertEquals(100L, found.get().orderId());
        // 출고 필터도 걸지 않는다 — 이미 등록한 건의 송장번호를 다시 확인하는 것이 이 화면의
        // 두 번째 용도다.
        verify(orderQueryPort).findOrders(777L, LocalDate.EPOCH, LocalDate.of(2026, 9, 2),
                100L, false, 1, 0L);
    }

    @Test
    void 남의_주문_상세는_빈_값이다() {
        when(orderQueryPort.findOrders(anyLong(), any(), any(), any(), anyBoolean(), anyInt(), anyLong()))
                .thenReturn(List.of());

        assertTrue(service.order(seller(), 100L).isEmpty());
    }

    @Test
    void 법인_조직은_조회_자체가_막힌다() {
        SellerScope corporate = new SellerScope(9L, "르무엘법인", OrgType.CORPORATE, null, MemberRole.OWNER);

        assertThrows(NotASellerException.class,
                () -> service.orders(corporate, new SellerOrderQuery(null, null, null, false, 0, 20)));
        assertThrows(NotASellerException.class, () -> service.order(corporate, 100L));
        // null 셀러 ID 가 포트에 닿으면 조건이 seller_id IS NULL 이 되어 미할당 주문이 전부 열린다.
        verifyNoInteractions(orderQueryPort);
    }
}
