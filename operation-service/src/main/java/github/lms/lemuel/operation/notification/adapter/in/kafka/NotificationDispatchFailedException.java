package github.lms.lemuel.operation.notification.adapter.in.kafka;

import github.lms.lemuel.operation.notification.application.ChannelResult;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 활성 채널 전부가 실패했다 — 이 알림은 어디에도 전달되지 않았다.
 *
 * <p>{@link IllegalStateException} 을 확장하는 이유는 shared-common
 * {@code KafkaConsumerErrorHandlingConfig} 의 "즉시 DLT" 분류에 걸리기 위함이다.
 * Kafka 재시도를 붙이지 않는 근거는 둘이다.
 * <ol>
 *   <li>채널은 이미 자체 재시도를 소진했다(채널별 timeout + 3회 백오프) — 2초 뒤 같은 SMTP 가
 *       살아날 확률에 오프셋을 걸어 둘 이유가 없다.</li>
 *   <li>dedupe 가 dispatch 직전에 eventId 를 선점하므로, 재배달은 dedupe 스킵(no-op)으로 끝나고
 *       컨테이너에는 "성공"으로 보인다. 즉 재시도는 유실을 고치지 못하고 <b>가린다</b>.</li>
 * </ol>
 *
 * <p>DLT 에 보존되면 사후 분석과 replay 가 가능하다. dedupe TTL(기본 30분)이 지난 뒤 replay 하면
 * 실제로 다시 발송된다.
 */
public class NotificationDispatchFailedException extends IllegalStateException {

    public NotificationDispatchFailedException(String topic, String eventId, List<ChannelResult.Failure> failures) {
        super("all channels failed on topic=%s eventId=%s — %s".formatted(topic, eventId,
                failures.stream()
                        .map(f -> "%s(%d attempts): %s".formatted(f.channel(), f.attempts(), f.error()))
                        .collect(Collectors.joining(", "))));
    }
}
