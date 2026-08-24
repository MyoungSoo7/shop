package github.lms.lemuel.operation.notification.adapter.in.kafka;

/**
 * 계약 토픽에 파싱 불가 페이로드가 들어왔다 — 계약 드리프트 신호.
 *
 * <p>재시도해도 같은 바이트가 다시 파싱 실패하므로 shared-common 의
 * {@code KafkaConsumerErrorHandlingConfig} 가 재시도 없이 즉시 DLT 로 격리한다
 * ({@link IllegalArgumentException} 이 "재시도 불가" 분류에 걸린다). 임의의 IAE 와 로그·분류에서
 * 구분되도록 전용 타입을 유지한다.
 *
 * <p>이전에는 warn 로그 + skip 이었다. 폴백 수신자에게 엉뚱한 GENERIC 알림을 만들지 않으려는
 * 의도였는데(그 의도는 여전히 유효 — 던져도 알림은 만들어지지 않는다), 결과적으로 원본 메시지가
 * 흔적 없이 사라졌다. 이제는 DLT 에 보존되어 사후 분석·replay 가 된다.
 */
public class UnparseableEventPayloadException extends IllegalArgumentException {

    public UnparseableEventPayloadException(String topic, String key, Throwable cause) {
        super("unparseable event payload on contract topic=%s key=%s".formatted(topic, key), cause);
    }
}
