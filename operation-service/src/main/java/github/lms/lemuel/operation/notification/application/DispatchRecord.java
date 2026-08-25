package github.lms.lemuel.operation.notification.application;

import java.time.Instant;
import java.util.List;

/**
 * 저널에 남은 발송 1건 — 운영 콘솔 읽기 모델.
 *
 * <p>도메인 {@code Notification} 과 일부러 분리한다. 저 쪽은 "보낼 것"이라 불변식(수신자·제목 필수)을
 * 강제하지만, 이 쪽은 "이미 일어난 일"이라 무엇이 저장돼 있든 있는 그대로 읽어야 한다.
 * 과거 행에 불변식을 다시 태우면 <b>조회가 예외로 죽는다</b>.
 *
 * @param channels 목록 조회에서는 비어 있다(N+1 을 만들지 않기 위해 상세 조회에서만 채운다).
 */
public record DispatchRecord(
        long id,
        String eventId,
        String type,
        String recipient,
        String subject,
        String body,
        String status,
        int channelsTotal,
        int channelsSucceeded,
        Long resentFromId,
        Instant createdAt,
        Instant completedAt,
        List<ChannelOutcome> channels
) {

    public DispatchRecord {
        channels = List.copyOf(channels);
    }

    /** 채널 1건의 확정 결과. {@link ChannelResult} 를 저장한 모양 그대로. */
    public record ChannelOutcome(String channel, String status, int attempts, String error, Instant createdAt) {
    }
}
