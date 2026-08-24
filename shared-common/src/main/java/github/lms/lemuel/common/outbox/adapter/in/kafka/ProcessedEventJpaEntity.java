package github.lms.lemuel.common.outbox.adapter.in.kafka;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEventJpaEntity {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected ProcessedEventJpaEntity() { }

    public ProcessedEventJpaEntity(String consumerGroup, UUID eventId, String eventType) {
        this.id = new ProcessedEventId(consumerGroup, eventId);
        this.eventType = eventType;
        this.processedAt = LocalDateTime.now();
    }

    public ProcessedEventId getId() { return id; }
    public String getEventType() { return eventType; }
    public LocalDateTime getProcessedAt() { return processedAt; }

    @Embeddable
    public static class ProcessedEventId implements Serializable {
        @Column(name = "consumer_group", nullable = false, length = 100)
        private String consumerGroup;

        @Column(name = "event_id", nullable = false)
        private UUID eventId;

        protected ProcessedEventId() { }

        public ProcessedEventId(String consumerGroup, UUID eventId) {
            this.consumerGroup = consumerGroup;
            this.eventId = eventId;
        }

        public String getConsumerGroup() { return consumerGroup; }
        public UUID getEventId() { return eventId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProcessedEventId that)) return false;
            return Objects.equals(consumerGroup, that.consumerGroup)
                    && Objects.equals(eventId, that.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, eventId);
        }
    }
}
