package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointLot;

/**
 * 포인트 도메인 이벤트 발행 포트 — 구현은 Outbox 어댑터다(직접 send 금지).
 *
 * <p>이 이벤트들의 소비자는 {@code account-service} 다. 포인트는 회사 입장에서 <b>부채</b>이고
 * 보너스·적립은 <b>판촉비</b>라, 잔고 변화가 GL 로 넘어가지 않으면 시산표가 현실과 어긋난다.
 * account-service 는 소비 전용이므로 발행은 전적으로 이쪽 책임이다.
 */
public interface PublishPointEventPort {

    /** 현금 충전 원금 — DR CASH / CR POINT_LIABILITY. */
    void pointCharged(PointAccount account, PointLot lot, String chargeReference);

    /** 보너스·구매적립·수기지급 — DR POINT_PROMOTION_EXPENSE / CR POINT_LIABILITY. */
    void pointGranted(PointAccount account, PointLot lot);

    /** 사용 — DR POINT_LIABILITY / CR CASH. 정산이 가정한 현금 유입을 상계한다. */
    void pointUsed(PointAccount account, PointEntry entry);

    /** 환불 복원 — DR CASH / CR POINT_LIABILITY (사용의 대칭). */
    void pointRestored(PointAccount account, PointEntry entry);

    /**
     * 적립 취소 — DR POINT_LIABILITY / CR POINT_PROMOTION_EXPENSE (판촉비 환입).
     * 소멸(이익 인식)과 상대계정이 다르므로 이벤트도 갈라야 한다.
     */
    void pointRevoked(PointAccount account, PointEntry entry);

    /** 소멸 — DR POINT_LIABILITY / CR POINT_BREAKAGE_INCOME. */
    void pointExpired(PointAccount account, PointLot lot, java.math.BigDecimal forfeitedAmount);
}
