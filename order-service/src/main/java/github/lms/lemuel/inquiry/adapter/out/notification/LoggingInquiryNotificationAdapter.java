package github.lms.lemuel.inquiry.adapter.out.notification;

import github.lms.lemuel.inquiry.application.port.out.NotifyInquiryPort;
import github.lms.lemuel.inquiry.domain.Inquiry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 문의 알림 — 로그로 남기는 기본 구현.
 *
 * <p>실제 발송 채널(알림톡·메일)을 붙이는 자리는 여기이며, 붙이더라도 <b>예외를 밖으로 내지
 * 않는다</b>는 규칙은 그대로다. 레거시가 정확히 그 반대였다 — 알림톡 발송이 문의 등록과 같은
 * {@code try} 안에 있어서, 발송 한 번 실패가 사용자에게 "등록 실패"로 나갔다. 행은 들어가 있었고
 * 사용자는 같은 문의를 다시 남겼다.
 *
 * <p>못 보낸 사실은 사라지지 않는다 — {@code WARN} 으로 남으므로 로그에서 셀 수 있다. 조용히
 * 삼키는 것과 응답에 섞지 않는 것은 다른 일이다.
 */
@Component
public class LoggingInquiryNotificationAdapter implements NotifyInquiryPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingInquiryNotificationAdapter.class);

    @Override
    public void notifyAsked(Inquiry inquiry) {
        try {
            log.info("[inquiry] 새 문의 inquiryId={} type={} userId={} productId={} orderId={}",
                    inquiry.id(), inquiry.type(), inquiry.userId(), inquiry.productId(), inquiry.orderId());
        } catch (RuntimeException e) {
            // 실패 로그에서 inquiry 를 다시 만지지 않는다 — 방금 터진 것이 바로 그 참조일 수 있다.
            log.warn("[inquiry] 등록 알림 실패 — 문의 등록 자체는 성공했다", e);
        }
    }

    @Override
    public void notifyAnswered(Inquiry inquiry) {
        try {
            log.info("[inquiry] 답변 등록 inquiryId={} userId={} answers={}",
                    inquiry.id(), inquiry.userId(), inquiry.answers().size());
        } catch (RuntimeException e) {
            log.warn("[inquiry] 답변 알림 실패 — 답변 등록 자체는 성공했다", e);
        }
    }
}
