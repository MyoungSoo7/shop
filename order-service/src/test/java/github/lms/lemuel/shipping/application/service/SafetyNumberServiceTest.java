package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.out.SafetyNumberPort;
import github.lms.lemuel.shipping.domain.SafetyNumber;
import github.lms.lemuel.shipping.domain.SafetyNumberStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SafetyNumberService — 안심번호 배정·회수")
class SafetyNumberServiceTest {

    private SafetyNumberPort port;
    private SafetyNumberService service;

    @BeforeEach
    void setUp() {
        port = mock(SafetyNumberPort.class);
        service = new SafetyNumberService(port, 7);
        when(port.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static SafetyNumber pooled(String number) {
        return SafetyNumber.rehydrate(1L, number, SafetyNumberStatus.AVAILABLE, null, null, null);
    }

    @Test
    @DisplayName("풀에서 하나를 집어 주문에 배정한다")
    void assignsFromPool() {
        when(port.findAssignedByOrderId(77L)).thenReturn(Optional.empty());
        when(port.claimAvailable()).thenReturn(Optional.of(pooled("050-9999-0001")));

        Optional<SafetyNumber> assigned = service.assignForOrder(77L);

        assertThat(assigned).isPresent();
        assertThat(assigned.get().getVirtualNumber()).isEqualTo("050-9999-0001");
        assertThat(assigned.get().getOrderId()).isEqualTo(77L);
        assertThat(assigned.get().getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 배정된 주문은 같은 번호를 돌려준다 — 재시도가 풀을 갉아먹지 않는다")
    void assignIsIdempotent() {
        SafetyNumber existing = SafetyNumber.rehydrate(1L, "050-9999-0001",
                SafetyNumberStatus.ASSIGNED, 77L, OffsetDateTime.now(), OffsetDateTime.now().plusDays(7));
        when(port.findAssignedByOrderId(77L)).thenReturn(Optional.of(existing));

        assertThat(service.assignForOrder(77L)).contains(existing);

        verify(port, never()).claimAvailable();
        verify(port, never()).save(any());
    }

    @Test
    @DisplayName("풀이 마르면 비어 있는 결과 — 배송 생성을 실패시키지 않는다")
    void poolExhaustedDoesNotThrow() {
        when(port.findAssignedByOrderId(77L)).thenReturn(Optional.empty());
        when(port.claimAvailable()).thenReturn(Optional.empty());

        assertThat(service.assignForOrder(77L)).isEmpty();
        verify(port, never()).save(any());
    }

    @Test
    @DisplayName("만료된 번호를 회수해 풀로 되돌린다")
    void releasesExpired() {
        OffsetDateTime now = OffsetDateTime.now();
        SafetyNumber expired = SafetyNumber.rehydrate(1L, "050-9999-0001",
                SafetyNumberStatus.ASSIGNED, 77L, now.minusDays(8), now.minusDays(1));
        when(port.findExpired(any(), anyInt())).thenReturn(List.of(expired));

        int released = service.releaseExpired(now, 100);

        assertThat(released).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(SafetyNumberStatus.AVAILABLE);
        assertThat(expired.getOrderId()).isNull();
        verify(port).save(expired);
    }

    @Test
    @DisplayName("회수 대상이 없으면 0 건")
    void nothingToRelease() {
        when(port.findExpired(any(), anyInt())).thenReturn(List.of());

        assertThat(service.releaseExpired(OffsetDateTime.now(), 100)).isZero();
        verify(port, never()).save(any());
    }

    @Test
    @DisplayName("주문 배정 조회는 포트에 위임한다")
    void findDelegates() {
        when(port.findAssignedByOrderId(5L)).thenReturn(Optional.empty());

        assertThat(service.findForOrder(5L)).isEmpty();
    }
}
