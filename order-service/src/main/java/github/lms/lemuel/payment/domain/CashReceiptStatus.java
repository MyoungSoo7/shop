package github.lms.lemuel.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 현금영수증 상태머신.
 *
 * <pre>
 *   REQUESTED ─┬─→ ISSUED ─→ CANCEL_REQUESTED ─┬─→ CANCELED
 *              │                               └─→ ISSUED   (취소 실패 → 원상태 복귀)
 *              └─→ FAILED
 * </pre>
 *
 * <p><b>발급과 취소가 모두 외부(국세청 연동) 왕복</b>이라 "요청했지만 아직 확정되지 않은" 중간 상태가
 * 반드시 필요하다. 요청 상태 없이 곧바로 결과 상태만 두면, 응답을 못 받은 건이 발급된 건인지
 * 안 된 건인지 알 수 없어 재시도가 이중 발급이 된다.
 *
 * <p>취소 실패가 {@code ISSUED} 로 되돌아오는 것이 핵심이다 — 취소에 실패했는데 CANCELED 로 두면
 * 국세청에는 발급이 살아 있는데 우리 장부에는 없는, 되돌리기 어려운 어긋남이 남는다.
 */
public enum CashReceiptStatus {

    /** 발급 요청 접수 — 아직 승인번호가 없다. */
    REQUESTED,
    /** 발급 완료 — 승인번호 보유. 유효한 영수증. */
    ISSUED,
    /** 취소 요청 접수 — 아직 국세청 취소 확정 전. */
    CANCEL_REQUESTED,
    /** 취소 완료 — 더 이상 유효하지 않다. */
    CANCELED,
    /** 발급 실패 — 재신청이 가능하도록 유효 건으로 세지 않는다. */
    FAILED;

    private static final Map<CashReceiptStatus, Set<CashReceiptStatus>> ALLOWED = Map.of(
            REQUESTED, EnumSet.of(ISSUED, FAILED),
            ISSUED, EnumSet.of(CANCEL_REQUESTED),
            CANCEL_REQUESTED, EnumSet.of(CANCELED, ISSUED),
            CANCELED, EnumSet.noneOf(CashReceiptStatus.class),
            FAILED, EnumSet.noneOf(CashReceiptStatus.class)
    );

    public boolean canTransitionTo(CashReceiptStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * "결제 1 건당 하나"를 셀 때 자리를 차지하는 상태인지.
     *
     * <p>FAILED·CANCELED 는 자리를 비운다 — 실패했거나 취소한 영수증 때문에 재발급이 영영 막히면
     * 고객은 세금 혜택을 잃는다. 반대로 REQUESTED 를 자리에서 빼면 응답 대기 중 재요청이
     * 이중 발급으로 이어진다.
     */
    public boolean occupiesActiveSlot() {
        return this == REQUESTED || this == ISSUED || this == CANCEL_REQUESTED;
    }

    /** 취소를 시작할 수 있는 상태인지(발급이 살아 있어야 취소할 것이 있다). */
    public boolean cancellable() {
        return this == ISSUED;
    }
}
