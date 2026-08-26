package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.application.port.out.SendGiftMessagePort;
import github.lms.lemuel.order.domain.GiftClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 발신 채널이 구성되지 않은 운영 환경의 자리 채우기.
 *
 * <p>알림톡 계약이 아직 없는 배포도 있다. 그때 기동을 막으면 선물 기능 하나 때문에 커머스 전체가
 * 못 뜬다. 그렇다고 {@link MockGiftMessageAdapter} 를 올릴 수는 없다 — 모의는 언제나 성공이라,
 * 보낸 사람 화면에는 "발송 완료"가 뜨는데 받는 사람에게는 아무것도 가지 않는다. 선물은 결제까지
 * 끝난 뒤라 그 침묵의 대가가 크다.
 *
 * <p>그래서 <b>명시적으로 실패</b>한다. 주문은 이미 만들어졌고 링크도 발급돼 있으므로, 채널을
 * 붙인 뒤 재발송으로 되살릴 수 있다.
 */
@Component
@Profile("prod")
@ConditionalOnProperty(name = "app.notification.alimtalk.enabled", havingValue = "false",
        matchIfMissing = true)
public class DisabledGiftMessageAdapter implements SendGiftMessagePort {

    private static final Logger log = LoggerFactory.getLogger(DisabledGiftMessageAdapter.class);

    private static final String REASON =
            "선물 안내 발신 채널이 구성되지 않았습니다(app.notification.alimtalk.enabled=false)";

    @Override
    public void sendGiftLink(GiftClaim claim, String claimUrl) {
        log.warn("선물 링크 발송 불가 — 채널 미구성: orderId={}", claim.getOrderId());
        throw new BusinessException(ErrorCode.GIFT_MESSAGE_CHANNEL_UNAVAILABLE, REASON);
    }

    @Override
    public void sendVerificationCode(GiftClaim claim, String code) {
        log.warn("선물 인증번호 발송 불가 — 채널 미구성: orderId={}", claim.getOrderId());
        throw new BusinessException(ErrorCode.GIFT_MESSAGE_CHANNEL_UNAVAILABLE, REASON);
    }
}
