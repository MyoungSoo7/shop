package github.lms.lemuel.order.adapter.out.notification;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.application.port.out.SendGiftMessagePort;
import github.lms.lemuel.order.domain.GiftClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 선물 안내 알림톡 발신.
 *
 * <p>수신 번호는 <b>회원 정보가 아니라 선물에 적힌 받는 사람 번호</b>다. 선물의 요점이 "결제한
 * 사람과 받을 사람이 다르다"는 것이라, 회원 번호로 보내면 링크가 보낸 사람에게 되돌아간다.
 *
 * <p>주문 상태 통지({@link AlimtalkOrderNotificationChannel})와 달리 <b>승인 템플릿이 없으면 조용히
 * 건너뛰지 않고 실패시킨다.</b> 상태 통지는 못 가도 고객이 주문 화면에서 확인할 수 있지만, 선물
 * 링크와 인증번호는 이 경로 말고는 받는 사람에게 닿을 길이 없다. 건너뛰면 아무도 모르는 채로
 * 선물이 사라진다.
 */
@Component
@Profile("prod")
@ConditionalOnProperty(name = "app.notification.alimtalk.enabled", havingValue = "true")
@EnableConfigurationProperties(AlimtalkProperties.class)
public class AlimtalkGiftMessageAdapter implements SendGiftMessagePort {

    private static final Logger log = LoggerFactory.getLogger(AlimtalkGiftMessageAdapter.class);

    private final AlimtalkSender sender;
    private final AlimtalkProperties properties;

    public AlimtalkGiftMessageAdapter(AlimtalkSender sender, AlimtalkProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void sendGiftLink(GiftClaim claim, String claimUrl) {
        String template = requireTemplate(properties.getGiftLinkTemplate(), "선물 링크");
        sender.send(claim.getRecipientPhone(), template, """
                [Lemuel] %s님이 선물을 보냈습니다.
                %s
                아래 링크에서 받는 주소를 남겨 주세요.
                %s""".formatted(claim.getRecipientName(), messageLine(claim), claimUrl));
        log.info("선물 링크 발송: orderId={}, giftClaimId={}", claim.getOrderId(), claim.getId());
    }

    @Override
    public void sendVerificationCode(GiftClaim claim, String code) {
        String template = requireTemplate(properties.getGiftCodeTemplate(), "선물 인증번호");
        sender.send(claim.getRecipientPhone(), template,
                "[Lemuel] 선물 수령 인증번호 %s (타인에게 알려주지 마세요)".formatted(code));
        // 인증번호 자체는 남기지 않는다. 발송 사실만 남긴다.
        log.info("선물 인증번호 발송: orderId={}, giftClaimId={}", claim.getOrderId(), claim.getId());
    }

    /** 선물 메시지가 없으면 빈 줄 — 템플릿 본문에 "null" 이 찍히는 것보다 낫다. */
    private static String messageLine(GiftClaim claim) {
        return claim.getMessage() == null ? "" : "\"" + claim.getMessage() + "\"";
    }

    private static String requireTemplate(String template, String what) {
        if (template == null || template.isBlank()) {
            throw new BusinessException(ErrorCode.GIFT_MESSAGE_CHANNEL_UNAVAILABLE,
                    what + " 알림톡 템플릿이 설정되지 않았습니다");
        }
        return template;
    }
}
