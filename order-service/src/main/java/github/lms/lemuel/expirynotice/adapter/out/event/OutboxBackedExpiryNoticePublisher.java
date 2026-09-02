package github.lms.lemuel.expirynotice.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.expirynotice.application.port.out.PublishExpiryNoticeEventPort;
import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 만료 예고를 {@code outbox_events} 로 영속시키는 어댑터.
 *
 * <p>토픽은 Outbox 규약에서 도출된다 — {@code aggregateType="ExpiryNotice"} +
 * {@code eventType="ExpiryNoticeUpcoming"} → {@code lemuel.expirynotice.upcoming}.
 *
 * <p>금액은 {@code toPlainString()} 문자열로 싣는다(이 저장소의 모든 금액 계약이 같다).
 * JSON number 로 실으면 받는 쪽 파서가 double 로 받아 정밀도를 잃는다.
 *
 * <p>페이로드에 <b>문구가 없다.</b> "3일 남았습니다" 같은 문장을 여기서 만들면 문안을 바꿀 때마다
 * order-service 를 배포해야 하고, 언어·채널별 분기까지 이쪽으로 딸려 온다. 사실(무엇이·얼마가·언제)만
 * 싣고 표현은 알림 슬라이스가 정한다.
 */
@Component
public class OutboxBackedExpiryNoticePublisher implements PublishExpiryNoticeEventPort {

    private static final String AGGREGATE_TYPE = "ExpiryNotice";
    private static final String EVENT_TYPE = "ExpiryNoticeUpcoming";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;
    private final TraceContextCapture traceContextCapture;

    public OutboxBackedExpiryNoticePublisher(SaveOutboxEventPort saveOutboxEventPort,
                                             @Qualifier("outboxObjectMapper") ObjectMapper objectMapper,
                                             TraceContextCapture traceContextCapture) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
        this.traceContextCapture = traceContextCapture;
    }

    @Override
    public void expiryUpcoming(ExpiringItem item, ExpiryNoticeStage stage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subjectType", item.subject().name());
        payload.put("subjectId", item.subjectId());
        payload.put("stage", stage.name());
        payload.put("userId", item.userId());
        payload.put("amount", plain(item.amount()));
        payload.put("expiresAt", item.expiresAt().toString());
        // 회원 식별자로 닿을 수 없는 대상(선물 수령권의 수령자)의 발송 힌트. 없으면 null 이다.
        payload.put("contactHint", item.contactHint());

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            // 알려진 페이로드의 직렬화 실패는 발생할 수 없는 인프라 오류다. 이벤트를 잃느니
            // 예외로 커밋을 되돌리는 편이 안전하다 — 되돌리면 원장 선점도 함께 사라져 다음 주기에 다시 잡힌다.
            throw new IllegalStateException("Failed to serialize expiry notice payload", exception);
        }

        OutboxEvent event = OutboxEvent.pending(
                AGGREGATE_TYPE,
                item.subject().name() + ":" + item.subjectId(),
                EVENT_TYPE,
                json,
                traceContextCapture.captureCurrentTraceParent());
        saveOutboxEventPort.save(event);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
