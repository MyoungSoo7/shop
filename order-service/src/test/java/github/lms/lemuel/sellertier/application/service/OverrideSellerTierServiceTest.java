package github.lms.lemuel.sellertier.application.service;

import github.lms.lemuel.sellertier.application.port.out.LoadTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.PublishSellerTierEventPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierAssignmentPort;
import github.lms.lemuel.sellertier.application.port.out.SaveTierHistoryPort;
import github.lms.lemuel.sellertier.domain.SellerTierGrade;
import github.lms.lemuel.sellertier.domain.TierAssignment;
import github.lms.lemuel.sellertier.domain.TierChangeReason;
import github.lms.lemuel.sellertier.domain.exception.SellerTierPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 등급 지정 (ADR 0031).
 *
 * <p>수기 UPDATE 를 대체하는 정식 경로다. 이 경로가 없으면 운영자는 결국 DB 를 직접 만지게 되고,
 * 그러면 이력도 유예도 남지 않아 ADR 0031 이 메우려던 공백이 그대로 돌아온다.
 */
@ExtendWith(MockitoExtension.class)
class OverrideSellerTierServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    @Mock LoadTierAssignmentPort loadPort;
    @Mock SaveTierAssignmentPort savePort;
    @Mock SaveTierHistoryPort historyPort;
    @Mock PublishSellerTierEventPort eventPort;

    private OverrideSellerTierService service;

    @BeforeEach
    void setUp() {
        service = new OverrideSellerTierService(loadPort, savePort, historyPort, eventPort, 3);
    }

    @Test @DisplayName("지정한 등급으로 바꾸고 저장한다")
    void overridesTier() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        TierAssignment result = service.override(7L, SellerTierGrade.STRATEGIC, "전략 파트너 계약", "admin", TODAY);

        assertThat(result.getTier()).isEqualTo(SellerTierGrade.STRATEGIC);
        verify(savePort).save(any(TierAssignment.class));
    }

    @Test @DisplayName("지정에는 보호 기간이 따라붙는다 — 다음 평가에서 곧바로 뒤집히지 않게")
    void overrideSetsGuard() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        TierAssignment result = service.override(7L, SellerTierGrade.VIP, "협상 결과", "admin", TODAY);

        assertThat(result.getDemotionGuardUntil()).isEqualTo(TODAY.plusMonths(3));
    }

    @Test @DisplayName("사유는 필수다 — 근거 없는 등급 변경이 이력에 남으면 감사가 무의미해진다")
    void memoIsRequired() {
        assertThatThrownBy(() -> service.override(7L, SellerTierGrade.VIP, "  ", "admin", TODAY))
                .isInstanceOf(SellerTierPolicyException.class);

        verify(savePort, never()).save(any());
    }

    @Test @DisplayName("이력에 ADMIN_OVERRIDE 와 작성자·사유가 남는다")
    void appendsHistoryWithOperator() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        service.override(7L, SellerTierGrade.VIP, "협상 결과", "alice", TODAY);

        ArgumentCaptor<SaveTierHistoryPort.TierHistoryEntry> c =
                ArgumentCaptor.forClass(SaveTierHistoryPort.TierHistoryEntry.class);
        verify(historyPort).append(c.capture());
        assertThat(c.getValue().reason()).isEqualTo(TierChangeReason.ADMIN_OVERRIDE);
        assertThat(c.getValue().changedBy()).isEqualTo("alice");
        assertThat(c.getValue().memo()).isEqualTo("협상 결과");
        assertThat(c.getValue().prevTier()).isEqualTo(SellerTierGrade.NORMAL);
    }

    @Test @DisplayName("관리자 지정은 근거 거래액이 없다 — 판정이 아니라 결정이다")
    void overrideHasNoBasisAmount() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        service.override(7L, SellerTierGrade.VIP, "협상 결과", "alice", TODAY);

        verify(eventPort).publishTierChanged(eq(7L), eq(SellerTierGrade.NORMAL), eq(SellerTierGrade.VIP),
                eq(TierChangeReason.ADMIN_OVERRIDE), eq(TODAY), isNull());
    }

    @Test @DisplayName("등급 이력이 없던 셀러도 지정할 수 있다 — NORMAL 에서 시작한 것으로 본다")
    void unknownSellerCanBeOverridden() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());

        TierAssignment result = service.override(7L, SellerTierGrade.VIP, "신규 계약", "admin", TODAY);

        assertThat(result.getTier()).isEqualTo(SellerTierGrade.VIP);
    }

    @Test @DisplayName("같은 등급으로 지정해도 유예는 새로 걸린다 — 보호가 목적인 지정")
    void sameTierStillResetsGuard() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(TierAssignment.rehydrate(
                7L, SellerTierGrade.VIP, TODAY.minusMonths(6), null, 1)));

        TierAssignment result = service.override(7L, SellerTierGrade.VIP, "등급 보호", "admin", TODAY);

        assertThat(result.getDemotionGuardUntil()).isEqualTo(TODAY.plusMonths(3));
        assertThat(result.getConsecutiveMissCount()).isZero();
    }

    @Test @DisplayName("근거 금액은 0 이 아니라 null 로 남는다 — 0원 실적과 '근거 없음'은 다른 사실이다")
    void historyHasNullBasisAmount() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(
                TierAssignment.initial(7L, SellerTierGrade.NORMAL, TODAY.minusMonths(6))));

        service.override(7L, SellerTierGrade.VIP, "협상", "admin", TODAY);

        ArgumentCaptor<SaveTierHistoryPort.TierHistoryEntry> c =
                ArgumentCaptor.forClass(SaveTierHistoryPort.TierHistoryEntry.class);
        verify(historyPort).append(c.capture());
        assertThat(c.getValue().basisAmount()).isNull();
    }
}
