package github.lms.lemuel.expirynotice.application.port.out;

import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;

/**
 * 만료 예고 이벤트 발행 — 구현은 Outbox 어댑터다(직접 send 금지).
 *
 * <p>이 서비스는 <b>알림을 보내지 않는다.</b> "곧 만료된다" 는 사실만 발행하고, 문구·채널·수신거부는
 * 알림 슬라이스(operation-service)가 정한다. 여기서 문자를 직접 쏘면 order-service 가 발송 채널의
 * 장애·요금·수신거부 정책까지 떠안게 된다.
 *
 * <p>원장 INSERT 와 같은 트랜잭션에서 Outbox 에 적히므로, <b>"원장에는 남았는데 이벤트는 안 나갔다"</b>
 * 가 구조적으로 불가능하다. 그 반대도 마찬가지다.
 */
public interface PublishExpiryNoticeEventPort {

    void expiryUpcoming(ExpiringItem item, ExpiryNoticeStage stage);
}
