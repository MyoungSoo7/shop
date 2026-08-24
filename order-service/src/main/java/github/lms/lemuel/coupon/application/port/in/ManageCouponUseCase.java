package github.lms.lemuel.coupon.application.port.in;

import github.lms.lemuel.coupon.domain.Coupon;

/**
 * 쿠폰 운영 조작 유스케이스 — 켜고 끄기.
 *
 * <p><b>왜 지금 생기는가</b>: {@code Coupon.activate()}/{@code deactivate()} 는 도메인에 있었지만
 * 부르는 경로가 없었다. 즉 <b>잘못 나간 쿠폰을 멈추는 유일한 방법이 DB 직접 UPDATE</b> 였다.
 * 할인은 나가는 돈이라, 멈추는 데 DBA 를 기다려야 하는 상태는 그 자체가 사고다.
 *
 * <p>삭제를 제공하지 않는 이유: 이미 발급·사용된 쿠폰을 지우면 사용 이력의 참조가 끊기고
 * 정산에서 그 할인이 어디서 왔는지 설명할 수 없게 된다. 끄는 것으로 충분하다.
 */
public interface ManageCouponUseCase {

    /** 쿠폰을 사용 가능 상태로 켠다. 이미 켜져 있으면 그대로 둔다. */
    Coupon activate(String code);

    /** 쿠폰을 즉시 중단한다. 이미 꺼져 있으면 그대로 둔다. */
    Coupon deactivate(String code);
}
