package github.lms.lemuel.shipping.adapter.out.carrier;

import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 택배사 연동이 구성되지 않았을 때의 자리 채우기 — <b>기본값</b>이다.
 *
 * <p>연동을 전제로 만들지 않았다는 사실이 배선에도 드러나야 한다. 계약이 없어도, 키가 없어도
 * 커머스는 뜨고 배송 타임라인은 내부 이력만으로 성립한다. 기동을 막는 쪽을 택하면 부수 기능
 * 하나가 전체를 인질로 잡는다.
 *
 * <p>{@link #enabled()} 가 {@code false} 라 호출부는 조회를 시도하지 않는다. 그래도
 * {@link #fetch} 를 구현해 두는 이유는, 규약을 어긴 호출이 있더라도 예외 대신 "쓸 수 없음"이라는
 * 값으로 되돌아오게 하기 위해서다.
 */
@Component
@ConditionalOnProperty(name = "app.carrier-tracking.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledCarrierTrackingAdapter implements CarrierTrackingPort {

    static final String REASON =
            "택배사 배송 조회 연동이 구성되지 않았습니다(app.carrier-tracking.enabled=false)";

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Result fetch(String carrier, String trackingNumber) {
        return Result.unavailable(REASON);
    }
}
