package github.lms.lemuel.common.config.kafka;

import io.micrometer.core.instrument.Counter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

/**
 * 재시도 소진 메시지를 보낼 DLT 목적지를 결정한다.
 *
 * <p>규칙: {@code <원본토픽>.DLT} 로 보내고 <b>파티션은 프로듀서에게 맡긴다</b>(-1).
 *
 * <p>원래는 원본 파티션 번호를 그대로 썼다. 그러면 "DLT 토픽은 원본과 파티션 수가 같아야 한다"는
 * 전제가 생기는데, 이 전제는 운영에서 조용히 깨진다 — 파티션은 늘리기만 가능하고(Kafka 제약),
 * 원본을 늘려도 {@code .DLT} 는 따라오지 않는다. 실측: {@code lemuel.payment.captured} 는 파티션 6개인데
 * {@code lemuel.payment.captured.DLT} 는 3개였다. 그 상태에서 파티션 3~5 의 레코드가 실패하면 존재하지 않는
 * 목적지로 향해 <b>격리 자체가 실패</b>한다 — 유실을 막으려고 만든 장치가 유실 지점이 된다.
 *
 * <p>파티션 번호 대신 <b>키</b>로 순서를 지킨다. recoverer 가 원본 key 를 그대로 실어 보내므로
 * 프로듀서 파티셔너가 같은 키를 같은 파티션에 모은다 = 키 단위 순서는 replay 에서도 유지된다.
 * 잃는 것은 "파티션 번호 일치"뿐이고, 그 자체에는 기능적 의미가 없다.
 *
 * <p>목적지를 정하는 김에 카운터를 올리고 ERROR 로그를 남긴다 — 이 카운터가
 * {@code monitoring/alert-rules.yml} 의 DLT 알람 임계 근거다.
 */
public final class DltDestinationResolver
        implements BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> {

    private static final Logger log = LoggerFactory.getLogger(DltDestinationResolver.class);

    /** Spring Kafka {@code DeadLetterPublishingRecoverer} 기본 명명 규칙. */
    private static final String DLT_SUFFIX = ".DLT";

    /**
     * 음수 파티션 = "지정하지 않음". {@code DeadLetterPublishingRecoverer} 가 이 경우 파티션을 비워
     * ProducerRecord 를 만들고, 프로듀서 파티셔너가 key 로 파티션을 정한다.
     */
    private static final int PRODUCER_CHOOSES_PARTITION = -1;

    private final Counter dltPublished;

    public DltDestinationResolver(Counter dltPublished) {
        this.dltPublished = dltPublished;
    }

    @Override
    public TopicPartition apply(ConsumerRecord<?, ?> record, Exception ex) {
        dltPublished.increment();
        log.error("[DLT] publishing record to DLT. topic={}, partition={}, offset={}, exception={}",
                record.topic(), record.partition(), record.offset(), ex.getClass().getSimpleName());
        return new TopicPartition(dltTopicOf(record.topic()), PRODUCER_CHOOSES_PARTITION);
    }

    /** 이미 {@code .DLT} 인 토픽은 다시 접미하지 않는다 — {@code .DLT.DLT} 무한 증식 방지. */
    private static String dltTopicOf(String topic) {
        return topic.endsWith(DLT_SUFFIX) ? topic : topic + DLT_SUFFIX;
    }
}
