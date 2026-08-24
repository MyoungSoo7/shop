package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.in.EvaluateSellerTiersUseCase.TierEvaluationReport;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerNetSalesPort;
import github.lms.lemuel.sellertier.application.port.out.LoadSellerNetSalesPort.SellerNetSales;
import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.SellerTierPolicy;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 등급 재산정 배치.
 *
 * <p>등급 변경은 수수료·지급주기·홀드백을 한꺼번에 바꾸므로 <b>실행 전 미리보기가 기본</b>이다.
 * 한 셀러의 실패가 나머지를 막아서도 안 된다.
 */
@ExtendWith(MockitoExtension.class)
class EvaluateSellerTiersServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    @Mock LoadSellerNetSalesPort netSalesPort;
    @Mock LoadTierAssignmentPort loadPort;
    @Mock SaveTierAssignmentPort savePort;
    @Mock SaveTierHistoryPort historyPort;
    @Mock github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort eventPort;

    private EvaluateSellerTiersService service;

    @BeforeEach
    void setUp() {
        SellerTierPolicy policy = SellerTierPolicy.of(
                new BigDecimal("500000000"), new BigDecimal("3000000000"));
        // 반영기는 실물을 쓴다 — 목으로 대체하면 "무엇을 저장/발행하는가"라는 이 테스트의 관심사가
        // 통째로 사라진다. 트랜잭션 경계만 프록시가 담당하고 로직은 여기서 그대로 검증된다.
        service = new EvaluateSellerTiersService(netSalesPort, loadPort,
                new SellerTierChangeProcessor(savePort, historyPort, eventPort),
                policy, /*missThreshold*/ 2);
    }

    private void sellers(SellerNetSales... rows) {
        when(netSalesPort.findNetSalesForLast12Months(any(), anyInt())).thenReturn(List.of(rows));
    }

    @Test @DisplayName("평가 대상이 없으면 아무 것도 하지 않는다")
    void noSellers() {
        sellers();

        TierEvaluationReport report = service.evaluate(TODAY, true, 500);

        assertThat(report.evaluated()).isZero();
        verifyNoInteractions(savePort, historyPort);
    }

    @Test @DisplayName("dryRun 은 저장도 이력도 남기지 않고 예상 결과만 낸다")
    void dryRunChangesNothing() {
        sellers(new SellerNetSales(7L, new BigDecimal("600000000")));
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());

        TierEvaluationReport report = service.evaluate(TODAY, true, 500);

        verifyNoInteractions(savePort, historyPort);
        assertThat(report.dryRun()).isTrue();
        assertThat(report.promoted()).isEqualTo(1);
    }

    @Test @DisplayName("승급은 저장하고 이력을 남긴다")
    void promotionIsPersistedWithHistory() {
        sellers(new SellerNetSales(7L, new BigDecimal("600000000")));
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        TierEvaluationReport report = service.evaluate(TODAY, false, 500);

        verify(savePort).save(any(TierAssignment.class));
        verify(historyPort).append(any());
        assertThat(report.promoted()).isEqualTo(1);
    }

    @Test @DisplayName("등급 유지는 저장하지 않는다 — 바뀐 게 없으면 쓰기도 이력도 없다")
    void holdWritesNothing() {
        sellers(new SellerNetSales(7L, new BigDecimal("100000000")));
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        TierEvaluationReport report = service.evaluate(TODAY, false, 500);

        verify(savePort, never()).save(any());
        verify(historyPort, never()).append(any());
        assertThat(report.held()).isEqualTo(1);
    }

    @Test @DisplayName("강등 보류는 미달 카운트를 저장하되 등급 이력은 남기지 않는다")
    void guardedPersistsCountButNotTierHistory() {
        sellers(new SellerNetSales(7L, new BigDecimal("100000000")));
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(TierAssignment.rehydrate(
                7L, SellerTierGrade.VIP, TODAY.minusMonths(6), null, 0)));

        TierEvaluationReport report = service.evaluate(TODAY, false, 500);

        verify(savePort).save(any(TierAssignment.class));   // 미달 카운트는 남아야 다음에 강등된다
        verify(historyPort, never()).append(any());
        assertThat(report.guarded()).isEqualTo(1);
    }

    @Test @DisplayName("처음 보는 셀러는 NORMAL 에서 시작해 평가한다")
    void unknownSellerStartsAtNormal() {
        sellers(new SellerNetSales(7L, new BigDecimal("100000000")));
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());

        TierEvaluationReport report = service.evaluate(TODAY, false, 500);

        assertThat(report.held()).isEqualTo(1);
    }

    @Test @DisplayName("한 셀러가 실패해도 나머지는 계속 평가한다")
    void oneFailureDoesNotStopBatch() {
        sellers(new SellerNetSales(7L, new BigDecimal("600000000")),
                new SellerNetSales(8L, new BigDecimal("600000000")));
        when(loadPort.findBySellerId(7L)).thenThrow(new RuntimeException("DB 잠금"));
        when(loadPort.findBySellerId(8L)).thenReturn(Optional.empty());

        TierEvaluationReport report = service.evaluate(TODAY, false, 500);

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.promoted()).isEqualTo(1);
    }

    @Test @DisplayName("행별 결과에 이전·이후 등급과 근거 거래액이 담긴다")
    void reportsPerSellerDetail() {
        sellers(new SellerNetSales(7L, new BigDecimal("600000000")));
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());

        TierEvaluationReport report = service.evaluate(TODAY, true, 500);

        var line = report.lines().get(0);
        assertThat(line.sellerId()).isEqualTo(7L);
        assertThat(line.fromTier()).isEqualTo("NORMAL");
        assertThat(line.toTier()).isEqualTo("VIP");
        assertThat(line.netSales()).isEqualByComparingTo("600000000");
    }

    @Test @DisplayName("조회 한도를 그대로 전달한다")
    void passesLimit() {
        sellers();

        service.evaluate(TODAY, true, 42);

        verify(netSalesPort).findNetSalesForLast12Months(TODAY, 42);
    }
}
