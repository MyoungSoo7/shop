package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SellerOrderView;
import github.lms.lemuel.seller.application.port.out.PublishSellerEventPort;
import github.lms.lemuel.seller.application.port.out.SellerOrderQueryPort;
import github.lms.lemuel.seller.application.port.out.ShipmentRequestPort;
import github.lms.lemuel.seller.domain.MemberRole;
import github.lms.lemuel.seller.domain.OrgType;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.exception.InsufficientSellerRoleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 송장 등록 — 순서가 이 클래스의 전부다: <b>소유 확인 → 행 남기기 → 발행</b>.
 *
 * <p>세 단계 각각이 빠졌을 때 무슨 일이 생기는지를 테스트로 고정한다. 이 서비스에서 소유 확인이
 * 빠지면 조회 IDOR 과 달리 <b>남의 주문 상태가 바뀐다</b>.
 */
class SellerShipmentServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-01T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private SellerOrderQueryPort orderQueryPort;
    private ShipmentRequestPort shipmentRequestPort;
    private PublishSellerEventPort publishPort;
    private SellerShipmentService service;

    @BeforeEach
    void setUp() {
        orderQueryPort = mock(SellerOrderQueryPort.class);
        shipmentRequestPort = mock(ShipmentRequestPort.class);
        publishPort = mock(PublishSellerEventPort.class);
        service = new SellerShipmentService(orderQueryPort, shipmentRequestPort, publishPort, FIXED);
    }

    private static SellerScope scope(MemberRole role) {
        return new SellerScope(7L, "명수상사", OrgType.SELLER, 777L, role);
    }

    private static SellerOrderView order() {
        return new SellerOrderView(100L, 200L, LocalDateTime.of(2026, 8, 30, 14, 0), false,
                new BigDecimal("12900"), BigDecimal.ZERO, new BigDecimal("12900"), "CARD",
                "PAID", 5001L, "사과 1kg", false, null, null, null);
    }

    private void ownsOrder(boolean owns) {
        when(orderQueryPort.findOrders(eq(777L), any(), any(), eq(100L), eq(false), eq(1), eq(0L)))
                .thenReturn(owns ? List.of(order()) : List.of());
    }

    @Test
    void 정상_등록은_확인_기록_발행_순으로_간다() {
        ownsOrder(true);
        when(shipmentRequestPort.record(100L, 777L, "CJ대한통운", "1234567890", 42L)).thenReturn(true);

        service.register(scope(MemberRole.OWNER), 42L, 100L, "CJ대한통운", "1234567890");

        InOrder order = inOrder(orderQueryPort, shipmentRequestPort, publishPort);
        order.verify(orderQueryPort).findOrders(anyLong(), any(), any(), anyLong(), anyBoolean(), anyInt(), anyLong());
        order.verify(shipmentRequestPort).record(100L, 777L, "CJ대한통운", "1234567890", 42L);
        // 발행이 마지막이다. 먼저 발행하면 행이 안 남은 채 출고가 나가고, 셀러 화면에는
        // 아무 흔적이 없다.
        order.verify(publishPort).shipmentRegistered(100L, 777L, "CJ대한통운", "1234567890");
    }

    @Test
    void 소유_확인은_스코프의_셀러로만_한다() {
        ownsOrder(true);
        when(shipmentRequestPort.record(anyLong(), anyLong(), any(), any(), anyLong())).thenReturn(true);

        service.register(scope(MemberRole.OWNER), 42L, 100L, "CJ대한통운", "1234567890");

        // 첫 인자가 스코프에서 온 셀러 ID 다. 요청에서 온 값이 들어오면 남의 주문에 송장을 찍는다.
        verify(orderQueryPort).findOrders(eq(777L), any(), any(), eq(100L), eq(false), eq(1), eq(0L));
    }

    @Test
    void 남의_주문이면_기록도_발행도_하지_않는다() {
        ownsOrder(false);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.register(scope(MemberRole.OWNER), 42L, 100L, "CJ대한통운", "1234567890"));

        // 없는 주문과 남의 주문을 구분하지 않는다 — 구분하면 주문번호를 훑어 남의 매출 규모를 셀 수 있다.
        assertTrue(thrown.getMessage().contains("orderId=100"), thrown.getMessage());
        verifyNoInteractions(shipmentRequestPort, publishPort);
    }

    @Test
    void 이미_등록된_주문이면_발행하지_않는다() {
        ownsOrder(true);
        when(shipmentRequestPort.record(100L, 777L, "CJ대한통운", "1234567890", 42L)).thenReturn(false);

        // 중복 판정은 DB 유니크 제약이 한다. 먼저 조회해 없으면 넣는 방식은 버튼을 두 번 누르는
        // 그 순간에 그대로 뚫린다.
        assertThrows(IllegalArgumentException.class,
                () -> service.register(scope(MemberRole.OWNER), 42L, 100L, "CJ대한통운", "1234567890"));
        verifyNoInteractions(publishPort);
    }

    @Test
    void STAFF_는_송장을_등록할_수_없다() {
        assertThrows(InsufficientSellerRoleException.class,
                () -> service.register(scope(MemberRole.STAFF), 42L, 100L, "CJ대한통운", "1234567890"));

        // 권한 검사가 첫 줄이라 조회조차 나가지 않는다.
        verifyNoInteractions(orderQueryPort, shipmentRequestPort, publishPort);
    }

    @Test
    void 빈_택배사나_빈_송장번호는_거절한다() {
        SellerScope owner = scope(MemberRole.OWNER);

        assertThrows(IllegalArgumentException.class,
                () -> service.register(owner, 42L, 100L, "   ", "1234567890"));
        assertThrows(IllegalArgumentException.class,
                () -> service.register(owner, 42L, 100L, "CJ대한통운", null));
        // 검증이 조회보다 앞이다 — 빈 값으로 조회를 쏘면 아무 소득 없이 DB 만 때린다.
        verifyNoInteractions(orderQueryPort, shipmentRequestPort, publishPort);
    }

    @Test
    void 컬럼_상한을_넘는_값은_자르지_않고_거절한다() {
        SellerScope owner = scope(MemberRole.OWNER);

        // 조용히 자르면 셀러는 자기가 입력한 송장번호로 조회된다고 믿는데 실제로는 다른 번호가 저장된다.
        assertThrows(IllegalArgumentException.class,
                () -> service.register(owner, 42L, 100L, "가".repeat(51), "1234567890"));
        assertThrows(IllegalArgumentException.class,
                () -> service.register(owner, 42L, 100L, "CJ대한통운", "1".repeat(101)));
        verify(shipmentRequestPort, never()).record(anyLong(), anyLong(), any(), any(), anyLong());
    }

    @Test
    void 앞뒤_공백은_다듬어_기록하고_발행한다() {
        ownsOrder(true);
        when(shipmentRequestPort.record(100L, 777L, "CJ대한통운", "1234567890", 42L)).thenReturn(true);

        service.register(scope(MemberRole.MANAGER), 42L, 100L, "  CJ대한통운 ", " 1234567890  ");

        // 다듬은 값이 기록과 발행 양쪽에 같이 가야 한다. 한쪽만 다듬으면 우리 행과 order-service
        // 의 배송정보가 다른 송장번호를 갖는다.
        verify(shipmentRequestPort).record(100L, 777L, "CJ대한통운", "1234567890", 42L);
        verify(publishPort).shipmentRegistered(100L, 777L, "CJ대한통운", "1234567890");
    }

    @Test
    void 소유_확인은_기간을_열어_두고_본다() {
        ownsOrder(true);
        when(shipmentRequestPort.record(anyLong(), anyLong(), any(), any(), anyLong())).thenReturn(true);

        service.register(scope(MemberRole.OWNER), 42L, 100L, "CJ대한통운", "1234567890");

        // 기본 30일 창을 적용하면 오래된 주문의 송장 등록이 "내 주문이 아니다" 로 거절된다.
        verify(orderQueryPort).findOrders(777L, LocalDate.EPOCH,
                LocalDate.of(2026, 9, 2), 100L, false, 1, 0L);
    }
}
