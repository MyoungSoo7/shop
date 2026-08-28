package github.lms.lemuel.partner.application.service;

import github.lms.lemuel.partner.application.port.dto.OrderExport;
import github.lms.lemuel.partner.application.port.dto.OrderQuery;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderPage;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderView;
import github.lms.lemuel.partner.application.port.out.PartnerSalesQueryPort;
import github.lms.lemuel.partner.domain.MemberRole;
import github.lms.lemuel.partner.domain.OrgType;
import github.lms.lemuel.partner.domain.PartnerScope;
import github.lms.lemuel.partner.domain.exception.NoSalesScopeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 목록·상세·CSV. 세 경로가 같은 조회 포트를 쓰고 셋 다 첫 줄에서 스코프를 통과한다.
 *
 * <p>이 파일이 지키는 것은 네 가지다.
 * <ol>
 *   <li><b>잘림을 값으로 들고 나온다.</b> 행수 제한이 없으면 백오피스가 멎고, 제한만 걸고 알리지
 *       않으면 사용자가 잘린 파일을 전량으로 믿고 정산에 쓴다 — 뒤쪽이 더 나쁘다.</li>
 *   <li><b>CSV 인젝션을 막는다.</b> 문자열 칸(상품명·주문상태·결제수단)은 전부 다른 서비스가
 *       만든 값이라 우리 쪽에서 안전하다고 가정할 근거가 없다.</li>
 *   <li><b>BOM 을 붙인다.</b> 없으면 엑셀이 CP949 로 읽어 상품명이 깨진다. 파일은 열리고 숫자도
 *       멀쩡해서 "인코딩 문제" 가 아니라 "데이터가 이상하다" 로 보고된다.</li>
 *   <li><b>상세는 기간을 열어 둔다.</b> 상세 링크는 목록 밖(메일·메모)에서도 열린다.</li>
 * </ol>
 */
class PartnerOrderServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);
    private static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 7, 31);
    private static final LocalDateTime CAPTURED = LocalDateTime.of(2026, 8, 27, 13, 40);

    private PartnerSalesQueryPort queryPort;
    private PartnerOrderService service;

    @BeforeEach
    void setUp() {
        queryPort = mock(PartnerSalesQueryPort.class);
        service = new PartnerOrderService(queryPort,
                Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST), 50_000);
    }

    private static PartnerScope seller() {
        return new PartnerScope(7L, "명수상사", OrgType.SELLER, 777L, MemberRole.OWNER);
    }

    private static PartnerScope corporate() {
        return new PartnerScope(9L, "르무엘법인", OrgType.CORPORATE, null, MemberRole.STAFF);
    }

    private static PartnerOrderView row() {
        return new PartnerOrderView(10231L, 55L, CAPTURED, false,
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"),
                "CARD", "PAID", 11L, "텀블러");
    }

    private static String csvOf(OrderExport export) {
        return new String(export.csv(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 목록

    @Test
    void 기간을_안_주면_오늘까지_최근_30일로_찾는다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 20, 0L))
                .thenReturn(List.of(row()));

        PartnerOrderPage page = service.orders(seller(), new OrderQuery(null, null, null, 0, 20));

        assertEquals(1, page.content().size());
        assertEquals(1L, page.totalElements());
        assertEquals(1, page.totalPages());
    }

    @Test
    void 총건수가_0_이면_두_번째_쿼리를_쏘지_않는다() {
        when(queryPort.countOrders(anyLong(), any(), any(), isNull())).thenReturn(0L);

        PartnerOrderPage page = service.orders(seller(), new OrderQuery(null, null, null, 0, 20));

        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalPages());
        // 결과가 없을 것이 확실한 쿼리다. 쏘면 DB 시간만 쓴다.
        verify(queryPort, never()).findOrders(anyLong(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void 다음_쪽은_offset_을_page_size_로_계산한다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(45L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 20, 40L)).thenReturn(List.of(row()));

        PartnerOrderPage page = service.orders(seller(), new OrderQuery(null, null, null, 2, 20));

        assertEquals(2, page.page());
        assertEquals(3, page.totalPages());   // ceil(45/20)
        verify(queryPort).findOrders(777L, DEFAULT_FROM, TODAY, null, 20, 40L);
    }

    @Test
    void 페이지_크기는_상한으로_접는다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);

        service.orders(seller(), new OrderQuery(null, null, null, 0, 100_000));

        // 거절하지 않고 접는다 — 이 값은 사용자가 손으로 만든 게 아니라 화면이 만든 것이고,
        // 여기서 400 을 내면 사용자가 고칠 방법이 없다. 다만 열어 두면 한 번에 멎는다.
        verify(queryPort).findOrders(777L, DEFAULT_FROM, TODAY, null, 200, 0L);
    }

    @Test
    void 페이지_크기_0_은_기본값_20_이_된다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);

        service.orders(seller(), new OrderQuery(null, null, null, -3, 0));

        // 음수 페이지도 0 으로 접는다 — 음수 offset 은 쿼리에서 그대로 터진다.
        verify(queryPort).findOrders(777L, DEFAULT_FROM, TODAY, null, 20, 0L);
    }

    @Test
    void 뒤집힌_기간은_그대로_보내고_빈_결과가_나온다() {
        LocalDate from = LocalDate.of(2026, 8, 10);
        LocalDate to = LocalDate.of(2026, 8, 1);
        when(queryPort.countOrders(777L, from, to, null)).thenReturn(0L);

        PartnerOrderPage page = service.orders(seller(), new OrderQuery(from, to, null, 0, 20));

        // 조용히 뒤집으면 사용자가 고른 것과 다른 결과를 사실처럼 보여주게 된다.
        assertTrue(page.content().isEmpty());
        verify(queryPort).countOrders(777L, from, to, null);
    }

    @Test
    void 목록도_스코프_없이는_시작하지_않는다() {
        assertThrows(NoSalesScopeException.class,
                () -> service.orders(corporate(), new OrderQuery(null, null, null, 0, 20)));

        verifyNoInteractions(queryPort);
    }

    // ------------------------------------------------------------------ 상세

    @Test
    void 상세는_기간을_열어_두고_주문번호로만_찾는다() {
        when(queryPort.findOrders(777L, LocalDate.EPOCH, TODAY.plusDays(1), 10231L, 1, 0L))
                .thenReturn(List.of(row()));

        Optional<PartnerOrderView> found = service.order(seller(), 10231L);

        // 기본 30일 창을 적용하면 "목록에는 있는데 상세는 없다" 가 된다 — 링크는 메일·메모에서도 열린다.
        assertTrue(found.isPresent());
        assertEquals(10231L, found.get().orderId());
    }

    @Test
    void 없는_주문이면_빈_값이다() {
        when(queryPort.findOrders(anyLong(), any(), any(), any(), anyInt(), anyLong()))
                .thenReturn(List.of());

        assertTrue(service.order(seller(), 999L).isEmpty());
    }

    @Test
    void 상세도_스코프_없이는_시작하지_않는다() {
        assertThrows(NoSalesScopeException.class, () -> service.order(corporate(), 10231L));

        verifyNoInteractions(queryPort);
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void 전량이_담기면_잘리지_않았다고_말한다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(row()));

        OrderExport export = service.export(seller(), new OrderQuery(null, null, null, 0, 0));

        assertFalse(export.truncated());
        assertEquals(1L, export.totalMatched());
        assertEquals(1, export.exportedRows());
        assertEquals("partner-orders_777_20260731-20260829.csv", export.filename());
    }

    @Test
    void 상한을_넘으면_자르고_잘렸다고_말한다() {
        PartnerOrderService capped = new PartnerOrderService(queryPort,
                Clock.fixed(TODAY.atStartOfDay(KST).toInstant(), KST), 2);
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1200L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 2, 0L))
                .thenReturn(List.of(row(), row()));

        OrderExport export = capped.export(seller(), new OrderQuery(null, null, null, 0, 0));

        // ★ 이 테스트가 이 클래스에서 가장 중요하다. 자르는 것만으로는 절반이고,
        //   전체 건수와 잘림 여부를 함께 돌려줘야 화면이 사용자에게 알릴 수 있다.
        assertTrue(export.truncated());
        assertEquals(1200L, export.totalMatched());
        assertEquals(2, export.exportedRows());
    }

    @Test
    void 결과가_없으면_머리줄만_있는_파일을_준다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(0L);

        OrderExport export = service.export(seller(), new OrderQuery(null, null, null, 0, 0));

        assertFalse(export.truncated());
        assertEquals(0, export.exportedRows());
        // 머리줄 + 그 뒤의 빈 조각. 데이터 행이 하나라도 있으면 3 조각이 된다.
        assertEquals(2, csvOf(export).split("\n", -1).length);
        verify(queryPort, never()).findOrders(anyLong(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void 머리줄은_BOM_으로_시작한다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(0L);

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        // BOM 이 없으면 엑셀이 CP949 로 읽어 한글이 전부 깨진다. 응답에 charset=UTF-8 을
        // 선언해도 엑셀은 그걸 보지 않는다.
        // \uFEFF 로 쓴다 - 눈에 안 보이는 문자라 리터럴로 박아 두면 편집기가 지워도 모른다.
        assertEquals('\uFEFF', csv.charAt(0));
        assertTrue(csv.startsWith("\uFEFF결제일시,추정여부,주문번호"), csv);
    }

    @Test
    void 한_행은_열_순서대로_적힌다() {
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(row()));

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        assertTrue(csv.endsWith("2026-08-27 13:40:00,,10231,55,11,\"텀블러\",\"PAID\",\"CARD\",50000,0,50000\n"), csv);
    }

    @Test
    void 모르는_값은_채우지_않고_빈_칸으로_둔다() {
        PartnerOrderView unknown = new PartnerOrderView(10231L, 55L, null, true,
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"),
                null, null, null, null);
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(unknown));

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        // 주문상태를 'CREATED' 로 메우면 취소된 주문이 정상으로 보인다. 추정 시각은 "추정" 으로 표시한다.
        assertTrue(csv.endsWith(",추정,10231,55,,,,,50000,0,50000\n"), csv);
    }

    @Test
    void 수식으로_시작하는_상품명은_엑셀이_실행하지_못하게_한다() {
        PartnerOrderView injected = new PartnerOrderView(10231L, 55L, CAPTURED, false,
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"),
                "CARD", "PAID", 11L, "=cmd|'/c calc'!A1");
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(injected));

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        // 상품명은 다른 서비스가 만든 값이다. 선행 =+-@ 앞에 작은따옴표를 붙여 문자열로 못박는다.
        assertTrue(csv.contains("\"'=cmd|'/c calc'!A1\""), csv);
    }

    @Test
    void 큰따옴표는_두_번_찍어_칸을_깨지_않는다() {
        PartnerOrderView quoted = new PartnerOrderView(10231L, 55L, CAPTURED, false,
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"),
                "CARD", "PAID", 11L, "12\" 모니터");
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(quoted));

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        assertTrue(csv.contains("\"12\"\" 모니터\""), csv);
    }

    @Test
    void 금액은_지수표기_없이_평문으로_적는다() {
        PartnerOrderView scientific = new PartnerOrderView(10231L, 55L, CAPTURED, false,
                new BigDecimal("1E+5"), BigDecimal.ZERO, new BigDecimal("1E+5"),
                "CARD", "PAID", 11L, "텀블러");
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(scientific));

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        // 1E+5 가 그대로 찍히면 엑셀에서 숫자로 안 읽힌다.
        assertTrue(csv.contains(",100000,0,100000\n"), csv);
        assertFalse(csv.contains("E+"), csv);
    }

    @Test
    void 실매출이_음수여도_0_으로_깎지_않는다() {
        PartnerOrderView refunded = new PartnerOrderView(10231L, 55L, CAPTURED, false,
                BigDecimal.ZERO, new BigDecimal("50000"), new BigDecimal("-50000"),
                "CARD", "REFUNDED", 11L, "텀블러");
        when(queryPort.countOrders(777L, DEFAULT_FROM, TODAY, null)).thenReturn(1L);
        when(queryPort.findOrders(777L, DEFAULT_FROM, TODAY, null, 1, 0L)).thenReturn(List.of(refunded));

        String csv = csvOf(service.export(seller(), new OrderQuery(null, null, null, 0, 0)));

        // 지난달 결제분이 이번 달에 환불되면 음수가 맞다. 깎으면 화면 합계와 실제 정산액이
        // 어긋나고 그 차이를 설명할 수 있는 사람이 없어진다.
        assertTrue(csv.contains(",0,50000,-50000\n"), csv);
    }

    @Test
    void 파일명에는_셀러와_기간이_들어간다() {
        when(queryPort.countOrders(eq(777L), any(), any(), isNull())).thenReturn(0L);

        OrderExport export = service.export(seller(),
                new OrderQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), null, 0, 0));

        // 여러 번 받아 쌓아 두는 사용이 기본이라, 파일명만 보고 무엇을 받은 것인지 알아야 한다.
        assertEquals("partner-orders_777_20260801-20260815.csv", export.filename());
    }

    @Test
    void CSV_도_스코프_없이는_시작하지_않는다() {
        assertThrows(NoSalesScopeException.class,
                () -> service.export(corporate(), new OrderQuery(null, null, null, 0, 0)));

        // 목록에만 검사를 두고 CSV 를 빠뜨리는 것이 이 구조의 전형적 사고다 —
        // 눈으로 확인하는 사람이 적어서 오래 산다.
        verifyNoInteractions(queryPort);
    }
}
