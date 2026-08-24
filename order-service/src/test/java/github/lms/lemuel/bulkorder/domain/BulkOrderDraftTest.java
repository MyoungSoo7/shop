package github.lms.lemuel.bulkorder.domain;

import github.lms.lemuel.bulkorder.domain.exception.BulkOrderInvariantViolationException;
import github.lms.lemuel.bulkorder.domain.exception.InvalidBulkOrderStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대량주문 초안 — 검증과 확정의 분리.
 *
 * <p>레거시 커머스(ssgb2e)에서 가져온 규율: 업로드가 곧 주문이 되면, 수백 행 파일의 뒷쪽 한 행이
 * 틀렸을 때 앞쪽 수백 건을 취소·환불로 되돌려야 한다.
 */
@DisplayName("BulkOrderDraft — 업로드 · 검증 · 확정")
class BulkOrderDraftTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 21, 14, 0);

    private static final List<BulkOrderColumnSpec> SPECS = List.of(
            new BulkOrderColumnSpec(0, "product_id", "상품번호", true, 18,
                    BulkOrderValidationType.NUMERIC, null),
            new BulkOrderColumnSpec(1, "quantity", "수량", true, 6,
                    BulkOrderValidationType.NUMERIC, null));

    private static BulkOrderDraft draft(List<List<String>> values) {
        List<BulkOrderRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            rows.add(BulkOrderRow.uploaded(i + 1, values.get(i)));
        }
        return BulkOrderDraft.upload(9L, "bulk.csv", rows, T0);
    }

    @Test
    @DisplayName("전 행 통과면 VALIDATED — 확정이 열린다")
    void allValid() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2"), List.of("101", "1")));

        assertThat(draft.validate(SPECS, T0)).isEqualTo(BulkOrderStatus.VALIDATED);
        assertThat(draft.getStatus().confirmable()).isTrue();
        assertThat(draft.validRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 행이라도 틀리면 REJECTED — 부분 확정을 열지 않는다")
    void oneBadRowRejectsWholeDraft() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2"), List.of("abc", "1")));

        assertThat(draft.validate(SPECS, T0)).isEqualTo(BulkOrderStatus.REJECTED);
        assertThat(draft.getStatus().confirmable()).isFalse();
        assertThat(draft.validRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("REJECTED 는 종단이 아니다 — 고쳐서 재검증하는 것이 정상 흐름이다")
    void rejectedCanBeRevalidated() {
        BulkOrderDraft draft = draft(List.of(List.of("abc", "2")));
        draft.validate(SPECS, T0);

        // 값을 고친 초안(같은 id 로 재구성됐다고 가정)
        BulkOrderDraft fixed = BulkOrderDraft.rehydrate(1L, 9L, "bulk.csv",
                List.of(BulkOrderRow.uploaded(1, List.of("100", "2"))),
                BulkOrderStatus.REJECTED, T0, T0);

        assertThat(fixed.validate(SPECS, T0.plusMinutes(1))).isEqualTo(BulkOrderStatus.VALIDATED);
    }

    @Test
    @DisplayName("검증하지 않은 초안은 확정할 수 없다")
    void uploadedIsNotConfirmable() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2")));

        assertThatThrownBy(draft::requireConfirmable)
                .isInstanceOf(InvalidBulkOrderStateException.class);
    }

    @Test
    @DisplayName("확정은 종단 — 같은 파일이 두 번 주문으로 나가지 않는다")
    void confirmedIsTerminal() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2")));
        draft.validate(SPECS, T0);
        draft.markConfirmed(T0.plusMinutes(1));

        assertThat(draft.getStatus().terminal()).isTrue();
        assertThatThrownBy(() -> draft.discard(T0.plusMinutes(2)))
                .isInstanceOf(InvalidBulkOrderStateException.class);
        assertThatThrownBy(() -> draft.validate(SPECS, T0.plusMinutes(2)))
                .isInstanceOf(InvalidBulkOrderStateException.class);
    }

    @Test
    @DisplayName("확정 중 일부 실패면 REJECTED 로 되돌려 재확정을 연다 — 성공한 주문은 살린다")
    void partialConfirmReopens() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2"), List.of("101", "1")));
        draft.validate(SPECS, T0);
        draft.getRows().get(0).markOrderCreated(555L);
        draft.getRows().get(1).markConfirmFailed("재고가 부족합니다");

        draft.markPartiallyConfirmed(T0.plusMinutes(1));

        assertThat(draft.getStatus()).isEqualTo(BulkOrderStatus.REJECTED);
        // 이미 주문이 나간 행은 재확정 대상에서 빠진다 — 재시도가 중복 주문이 되지 않는 근거
        assertThat(draft.pendingRows()).isEmpty();
    }

    @Test
    @DisplayName("이미 주문이 나간 행에 다시 주문을 붙일 수 없다 — 중복 주문의 마지막 방어선")
    void rowRejectsSecondOrder() {
        BulkOrderRow row = BulkOrderRow.uploaded(1, List.of("100", "2"));
        row.validate(SPECS);
        row.markOrderCreated(555L);

        assertThatThrownBy(() -> row.markOrderCreated(556L))
                .isInstanceOf(BulkOrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("남의 초안은 다룰 수 없다 — 파일에 수백 명의 이름·연락처·주소가 들어 있다")
    void ownershipIsChecked() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2")));

        assertThat(draft.ownedBy(9L)).isTrue();
        assertThat(draft.ownedBy(10L)).isFalse();
    }

    @Test
    @DisplayName("데이터 행이 없는 파일은 초안이 되지 않는다")
    void emptyFileRejected() {
        assertThatThrownBy(() -> BulkOrderDraft.upload(9L, "empty.csv", List.of(), T0))
                .isInstanceOf(BulkOrderInvariantViolationException.class);
    }

    @Test
    @DisplayName("폐기도 종단 — 폐기한 초안이 되살아나지 않는다")
    void discardIsTerminal() {
        BulkOrderDraft draft = draft(List.of(List.of("100", "2")));
        draft.discard(T0);

        assertThat(draft.getStatus()).isEqualTo(BulkOrderStatus.DISCARDED);
        assertThatThrownBy(() -> draft.validate(SPECS, T0.plusMinutes(1)))
                .isInstanceOf(InvalidBulkOrderStateException.class);
    }
}
