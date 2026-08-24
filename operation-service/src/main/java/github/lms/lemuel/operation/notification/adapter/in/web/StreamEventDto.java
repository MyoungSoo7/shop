package github.lms.lemuel.operation.notification.adapter.in.web;

import github.lms.lemuel.operation.notification.domain.StreamEvent;

/** 푸시된 알림 1건의 전송 형식(프론트 계약). */
public record StreamEventDto(
        long id,
        String type,
        String recipient,
        String subject,
        String body,
        String eventId,
        String occurredAt
) {

    public static StreamEventDto from(StreamEvent event) {
        return new StreamEventDto(
                event.seq(),
                event.notification().type().name(),
                event.recipient(),
                event.notification().subject(),
                event.notification().body(),
                event.notification().eventId(),
                event.occurredAt().toString());
    }
}
