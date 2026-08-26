package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.order.application.port.out.SendGiftMessagePort;
import github.lms.lemuel.order.domain.GiftClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 선물 안내 모의 발신 — 개발·테스트 환경의 자리 표시자.
 *
 * <p><b>여기서만 인증번호를 로그에 남긴다.</b> 실제 문자가 나가지 않는 환경에서 6자리를 볼 방법이
 * 없으면 선물 흐름 자체를 손으로 확인할 수 없다. 대신 {@code @Profile("!prod")} 로 운영에서는
 * 아예 등록되지 않는다 — 운영 로그에 인증번호가 남으면 로그 열람 권한이 곧 남의 선물 수령
 * 권한이 된다.
 *
 * <p>링크 URL 도 같이 남긴다. 평문 토큰은 발급 순간에만 존재해서, 로그에 없으면 개발자가
 * 수령 화면을 열어 볼 방법이 없다.
 */
@Component
@Profile("!prod")
public class MockGiftMessageAdapter implements SendGiftMessagePort {

    private static final Logger log = LoggerFactory.getLogger(MockGiftMessageAdapter.class);

    @Override
    public void sendGiftLink(GiftClaim claim, String claimUrl) {
        log.warn("선물 링크 모의 발송 — 실제 발신 아님: orderId={}, 수령자={}, url={}",
                claim.getOrderId(), claim.maskedRecipientPhone(), claimUrl);
    }

    @Override
    public void sendVerificationCode(GiftClaim claim, String code) {
        log.warn("선물 인증번호 모의 발송 — 실제 발신 아님: orderId={}, 수령자={}, code={}",
                claim.getOrderId(), claim.maskedRecipientPhone(), code);
    }
}
