package github.lms.lemuel.payment.application.service;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.payment.application.port.out.LoadRefundPort;
import github.lms.lemuel.payment.application.port.out.SaveRefundPort;
import github.lms.lemuel.payment.domain.Refund;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefundLifecycle — 환불 이력 독립 트랜잭션 전이")
class RefundLifecycleTest {

    @Mock LoadRefundPort loadRefundPort;
    @Mock SaveRefundPort saveRefundPort;
    @Mock OpsSignalPort opsSignalPort;
    @InjectMocks RefundLifecycle lifecycle;

    @Test
    @DisplayName("begin — 기존 행 있으면 재사용")
    void begin_existing() {
        Refund existing = Refund.request(1L, new BigDecimal("100"), "key-1", "reason");
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(existing));
        Refund r = lifecycle.begin(1L, new BigDecimal("100"), "key-1", "reason");
        assertThat(r).isSameAs(existing);
        verify(saveRefundPort, never()).save(any());
    }

    @Test
    @DisplayName("begin — 없으면 새로 INSERT")
    void begin_new() {
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "key-2")).thenReturn(Optional.empty());
        when(saveRefundPort.save(any())).thenAnswer(i -> i.getArgument(0));
        Refund r = lifecycle.begin(1L, new BigDecimal("50"), "key-2", "reason");
        assertThat(r.getIdempotencyKey()).isEqualTo("key-2");
        verify(saveRefundPort).save(any());
    }

    @Test
    @DisplayName("begin — INSERT 경합(DataIntegrityViolation) 시 재조회 재사용")
    void begin_race() {
        Refund raced = Refund.request(1L, new BigDecimal("50"), "key-3", "reason");
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "key-3"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        when(saveRefundPort.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        Refund r = lifecycle.begin(1L, new BigDecimal("50"), "key-3", "reason");
        assertThat(r).isSameAs(raced);
    }

    @Test
    @DisplayName("begin — 경합 후에도 못 찾으면 원 예외 전파")
    void begin_race_rethrow() {
        when(loadRefundPort.findByPaymentIdAndIdempotencyKey(1L, "key-4"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(saveRefundPort.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThatThrownBy(() -> lifecycle.begin(1L, new BigDecimal("50"), "key-4", "reason"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("fail — FAILED 기록 + ops 신호 emit")
    void fail_records() {
        Refund refund = Refund.request(1L, new BigDecimal("100"), "k", "r");
        refund.assignId(77L);
        when(loadRefundPort.findById(77L)).thenReturn(Optional.of(refund));
        lifecycle.fail(77L, "PG timeout");
        assertThat(refund.getStatus()).isEqualTo(Refund.Status.FAILED);
        verify(saveRefundPort).save(refund);
        verify(opsSignalPort).emit(eq(OpsSignalCategory.PAYMENT_FAILED), eq("refund"), eq("77"), any(Map.class));
    }

    @Test
    @DisplayName("fail — 대상 행 없으면 조용히 종료")
    void fail_missing() {
        when(loadRefundPort.findById(1L)).thenReturn(Optional.empty());
        lifecycle.fail(1L, "reason");
        verify(saveRefundPort, never()).save(any());
        verify(opsSignalPort, never()).emit(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("fail — 이미 COMPLETED 면 실패 기록 생략")
    void fail_alreadyCompleted() {
        Refund refund = Refund.request(1L, new BigDecimal("100"), "k", "r");
        refund.markCompleted();
        when(loadRefundPort.findById(2L)).thenReturn(Optional.of(refund));
        lifecycle.fail(2L, "reason");
        verify(saveRefundPort, never()).save(any());
        verify(opsSignalPort, never()).emit(any(), anyString(), anyString(), any());
    }
}
