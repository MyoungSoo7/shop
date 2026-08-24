package github.lms.lemuel.common.outbox.application.port.out;

import github.lms.lemuel.common.outbox.domain.OutboxEvent;

import java.util.concurrent.CompletableFuture;

/**
 * 외부 이벤트 시스템(현재: Spring ApplicationEventPublisher, 향후: Kafka/RabbitMQ)으로
 * outbox 레코드를 발행하는 포트.
 *
 * <p>Kafka 전환 시점에는 이 포트의 Kafka 어댑터 구현체만 추가하면 되므로
 * 도메인 서비스와 스케줄러는 건드릴 필요가 없다.
 */
public interface PublishExternalEventPort {
    /**
     * 이벤트를 외부 버스로 발행 (동기). 실패 시 RuntimeException.
     */
    void publish(OutboxEvent event);

    /**
     * 이벤트를 비동기로 발행. 배치 폴러가 여러 이벤트를 한꺼번에 in-flight 로 띄워
     * 라운드트립을 병렬화하기 위한 경로다.
     *
     * <p>기본 구현은 동기 {@link #publish} 를 감싼 즉시 완료 future — Kafka 어댑터만
     * 진짜 비동기 send 를 반환하도록 오버라이드한다. future 가 예외로 완료되면 발행 실패다.
     */
    default CompletableFuture<Void> publishAsync(OutboxEvent event) {
        try {
            publish(event);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
