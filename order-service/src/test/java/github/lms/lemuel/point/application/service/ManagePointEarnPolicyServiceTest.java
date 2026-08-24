package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.ManagePointEarnPolicyUseCase.ClosePolicyCommand;
import github.lms.lemuel.point.application.port.in.ManagePointEarnPolicyUseCase.RegisterPolicyCommand;
import github.lms.lemuel.point.application.port.out.ManagePointEarnPolicyPort;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnScope;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 적립률 정책 편집 — 행을 고치지 않고 <b>종료 + 신규 등록</b>으로만 바꾼다(ADR 0032).
 *
 * <p>서비스가 지는 판단은 <b>시간 방향</b> 하나다: 이미 지나간 구간은 건드릴 수 없다.
 * 이미 적립된 로트는 그 시점 요율의 스냅샷이라 재계산되지 않으므로, 과거 요율을 바꾸면
 * 표와 원장이 서로 다른 말을 하게 된다.
 */
@ExtendWith(MockitoExtension.class)
class ManagePointEarnPolicyServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    @Mock
    ManagePointEarnPolicyPort port;

    ManagePointEarnPolicyService service;

    @BeforeEach
    void setUp() {
        service = new ManagePointEarnPolicyService(port);
    }

    private static RegisterPolicyCommand command(LocalDate from, LocalDate to) {
        return new RegisterPolicyCommand(PointEarnScope.GLOBAL, "-", new BigDecimal("0.01000"),
                365, from, to, "기본 적립률 1%", "admin:1");
    }

    private static PointEarnPolicy existing(Long id, LocalDate from, LocalDate to) {
        return PointEarnPolicy.rehydrate(id, PointEarnScope.GLOBAL, "-", new BigDecimal("0.00500"),
                365, from, to, "구 요율", "admin:1");
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("오늘 발효는 허용한다")
        void allowsToday() {
            when(port.save(any())).thenAnswer(call -> call.getArgument(0));

            service.register(command(TODAY, null), TODAY);

            ArgumentCaptor<PointEarnPolicy> captor = ArgumentCaptor.forClass(PointEarnPolicy.class);
            verify(port).save(captor.capture());
            assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(TODAY);
            assertThat(captor.getValue().getEarnRate()).isEqualByComparingTo("0.01000");
        }

        @Test
        @DisplayName("미래 발효는 허용한다 — 예고된 요율 변경이 정상 사용법이다")
        void allowsFuture() {
            when(port.save(any())).thenAnswer(call -> call.getArgument(0));

            service.register(command(TODAY.plusDays(30), null), TODAY);

            verify(port).save(any());
        }

        @Test
        @DisplayName("소급 발효는 거절한다 — 이미 적립된 로트는 그때 요율의 스냅샷이라 재계산되지 않는다")
        void rejectsRetroactive() {
            RegisterPolicyCommand retroactive = command(TODAY.minusDays(1), null);

            assertThatThrownBy(() -> service.register(retroactive, TODAY))
                    .isInstanceOf(InvalidPointStateException.class)
                    .hasMessageContaining("소급");

            verify(port, never()).save(any());
        }

        @Test
        @DisplayName("도메인 검증(요율 범위·유효기간·사유)은 그대로 통과시킨다")
        void delegatesDomainValidation() {
            RegisterPolicyCommand negativeRate = new RegisterPolicyCommand(
                    PointEarnScope.GLOBAL, "-", new BigDecimal("-0.1"), 365, TODAY, null,
                    "잘못된 요율", "admin:1");

            assertThatThrownBy(() -> service.register(negativeRate, TODAY))
                    .isInstanceOf(InvalidPointStateException.class);

            verify(port, never()).save(any());
        }
    }

    @Nested
    @DisplayName("종료")
    class Close {

        @Test
        @DisplayName("종료일을 지정하면 그 날짜로 끊는다 — 반열림이라 그날부터 적용되지 않는다")
        void closesWithDate() {
            LocalDate cutoff = TODAY.plusDays(10);
            when(port.findById(1L)).thenReturn(Optional.of(existing(1L, TODAY.minusDays(100), null)));
            when(port.close(1L, cutoff)).thenReturn(Optional.of(existing(1L, TODAY.minusDays(100), cutoff)));

            Optional<PointEarnPolicy> closed = service.close(
                    new ClosePolicyCommand(1L, cutoff, "admin:1"), TODAY);

            assertThat(closed).isPresent();
            assertThat(closed.orElseThrow().getEffectiveTo()).isEqualTo(cutoff);
        }

        @Test
        @DisplayName("오늘로 끊을 수 있다 — 즉시 중단이 정상 조작이다")
        void closesToday() {
            when(port.findById(1L)).thenReturn(Optional.of(existing(1L, TODAY.minusDays(100), null)));
            when(port.close(1L, TODAY)).thenReturn(Optional.of(existing(1L, TODAY.minusDays(100), TODAY)));

            assertThat(service.close(new ClosePolicyCommand(1L, TODAY, "admin:1"), TODAY)).isPresent();
        }

        @Test
        @DisplayName("과거로는 끊을 수 없다 — 그 구간 적립은 이미 일어났다")
        void rejectsPastCutoff() {
            // 날짜 형식 검증이 조회보다 먼저다 — 없는 id 로 과거 날짜를 보내도 DB 를 때리지 않는다.
            ClosePolicyCommand past = new ClosePolicyCommand(1L, TODAY.minusDays(1), "admin:1");
            assertThatThrownBy(() -> service.close(past, TODAY))
                    .isInstanceOf(InvalidPointStateException.class)
                    .hasMessageContaining("과거");

            verify(port, never()).close(anyLong(), any());
        }

        @Test
        @DisplayName("시작일보다 이른 종료일은 거절한다 — 빈 구간이 된다")
        void rejectsCutoffBeforeStart() {
            LocalDate start = TODAY.plusDays(30);
            when(port.findById(1L)).thenReturn(Optional.of(existing(1L, start, null)));

            ClosePolicyCommand tooEarly = new ClosePolicyCommand(1L, TODAY.plusDays(10), "admin:1");
            assertThatThrownBy(() -> service.close(tooEarly, TODAY))
                    .isInstanceOf(InvalidPointStateException.class)
                    .hasMessageContaining("시작일");

            verify(port, never()).close(anyLong(), any());
        }

        @Test
        @DisplayName("종료일이 없으면 거절한다 — 무기한으로 되돌리는 조작은 없다")
        void rejectsNullCutoff() {
            ClosePolicyCommand noDate = new ClosePolicyCommand(1L, null, "admin:1");

            assertThatThrownBy(() -> service.close(noDate, TODAY))
                    .isInstanceOf(InvalidPointStateException.class);

            verify(port, never()).close(anyLong(), any());
        }

        @Test
        @DisplayName("없는 정책이면 비어 있는 결과를 준다 — 404 판단은 어댑터 몫이다")
        void missingPolicy() {
            when(port.findById(99L)).thenReturn(Optional.empty());

            assertThat(service.close(new ClosePolicyCommand(99L, TODAY, "admin:1"), TODAY)).isEmpty();
        }
    }
}
