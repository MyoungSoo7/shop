package github.lms.lemuel.common.opssignal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOpsSignalPublisherTest {

    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    /** 프로덕션과 동일하게 java.time 직렬화 가능한 ObjectMapper (JacksonCompatConfig 와 동일). */
    private static ObjectMapper prodMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private KafkaOpsSignalPublisher publisher(ObjectMapper mapper) {
        return new KafkaOpsSignalPublisher(kafkaTemplate, mapper, "lemuel-test");
    }

    @Test
    void 카테고리_토픽으로_entityId_를_key_로_발행하고_event_id_헤더를_단다() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher(prodMapper()).emit(OpsSignalCategory.SETTLEMENT_FAILED, "payout", "42",
                Map.of("reason", "FIRM_BANKING_TIMEOUT"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("lemuel.ops.settlement.failed");
        assertThat(record.key()).isEqualTo("42");
        assertThat(record.value()).contains("SETTLEMENT_FAILED").contains("FIRM_BANKING_TIMEOUT")
                .contains("lemuel-test");
        assertThat(record.headers().lastHeader("event_id")).isNotNull();
    }

    @Test
    void 직렬화_실패해도_예외를_던지지_않고_send_도_하지_않는다() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

        assertThatCode(() -> publisher(failing).emit(OpsSignalCategory.ORDER_FAILED, "order", "1", null))
                .doesNotThrowAnyException();
    }

    @Test
    void send_가_동기적으로_던져도_삼킨다() {
        lenient().when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new RuntimeException("broker down"));

        assertThatCode(() -> publisher(prodMapper()).emit(OpsSignalCategory.STOCK_DEPLETED, "variant", "7", null))
                .doesNotThrowAnyException();
    }

    @Test
    void null_attributes_는_빈_맵으로_직렬화된다() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> publisher(prodMapper()).emit(OpsSignalCategory.PAYMENT_FAILED, "refund", "9", null))
                .doesNotThrowAnyException();
        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }
}
