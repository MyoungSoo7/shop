package github.lms.lemuel.bulkorder.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 대량주문 초안 상태머신.
 *
 * <pre>
 *   UPLOADED ─┬─→ VALIDATED ─┬─→ CONFIRMED
 *             │              └─→ DISCARDED
 *             ├─→ REJECTED ──┬─→ VALIDATED   (고쳐서 재검증)
 *             │              └─→ DISCARDED
 *             └─→ DISCARDED
 * </pre>
 *
 * <p><b>검증과 확정을 분리하는 것</b>이 레거시 커머스(ssgb2e)에서 가져온 핵심이다. 저쪽은
 * 업로드 → {@code orderMultiUploadCheck}(검증·오류행 표시) → 임시주문 → {@code tmpOrderToRealOrder}
 * 로 확정이 갈라져 있었다. 한 번에 처리하면 수백 행짜리 파일에서 뒷쪽 한 행이 틀렸을 때
 * 앞쪽 수백 건이 이미 실주문으로 나가 있고, 그것을 되돌리는 것이 곧 취소·환불 작업이 된다.
 *
 * <p>{@link #REJECTED} 가 종단이 아닌 이유: 틀린 셀만 고쳐 다시 검증하는 것이 정상 흐름이다.
 * 여기서 끊으면 운영자는 파일 전체를 다시 올려야 한다.
 */
public enum BulkOrderStatus {

    /** 파일이 올라와 행으로 쪼개진 상태. 아직 검증하지 않았다. */
    UPLOADED,
    /** 전 행 통과 — 확정 가능. */
    VALIDATED,
    /** 오류 행이 있다 — 고쳐서 재검증하거나 폐기한다. */
    REJECTED,
    /** 실주문으로 전환됨(종단). */
    CONFIRMED,
    /** 폐기(종단). */
    DISCARDED;

    private static final Map<BulkOrderStatus, Set<BulkOrderStatus>> ALLOWED = Map.of(
            UPLOADED, EnumSet.of(VALIDATED, REJECTED, DISCARDED),
            VALIDATED, EnumSet.of(CONFIRMED, DISCARDED, REJECTED),
            REJECTED, EnumSet.of(VALIDATED, REJECTED, DISCARDED),
            CONFIRMED, EnumSet.noneOf(BulkOrderStatus.class),
            DISCARDED, EnumSet.noneOf(BulkOrderStatus.class)
    );

    public boolean canTransitionTo(BulkOrderStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** 확정할 수 있는 상태인지 — 전 행이 통과했을 때만. */
    public boolean confirmable() {
        return this == VALIDATED;
    }

    /** 종단(더 이상 손댈 수 없는) 상태인지. */
    public boolean terminal() {
        return this == CONFIRMED || this == DISCARDED;
    }
}
