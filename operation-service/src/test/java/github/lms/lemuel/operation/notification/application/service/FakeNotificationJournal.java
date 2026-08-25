package github.lms.lemuel.operation.notification.application.service;

import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournal;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournalQuery;
import github.lms.lemuel.operation.notification.domain.Notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 저널의 인메모리 fake — 실 DB 의 UNIQUE 제약을 <b>흉내만</b> 낸다(모양 검증용).
 *
 * <p>진짜 멱등 보장은 여기서 증명할 수 없다. 그 질문(동시 INSERT 에서 한 쪽만 통과하는가)의
 * 답은 인덱스에 있고 {@code NotificationJournalIntegrationTest} 가 실 PostgreSQL 로 검증한다.
 * 이 클래스는 "디스패처가 L2 판정에 따라 팬아웃을 멈추는가" 같은 <b>배선</b>만 본다.
 */
final class FakeNotificationJournal implements NotificationJournal, NotificationJournalQuery {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Long> byEventId = new LinkedHashMap<>();
    private final Map<Long, DispatchRecord> records = new LinkedHashMap<>();
    private final List<String> beganEventIds = new ArrayList<>();

    /** true 면 begin 이 DB 장애를 흉내낸다 — fail-open 이 지켜지는지 보기 위해. */
    private boolean broken;

    void breakStorage() {
        this.broken = true;
    }

    List<String> beganEventIds() {
        return List.copyOf(beganEventIds);
    }

    @Override
    public synchronized Optional<Long> begin(Notification notification) {
        String eventId = notification.eventId();
        beganEventIds.add(eventId);
        if (broken) {
            return Optional.of(-1L); // 어댑터의 fail-open 계약과 같은 값
        }
        if (eventId != null && byEventId.containsKey(eventId)) {
            return Optional.empty();
        }
        long id = sequence.incrementAndGet();
        if (eventId != null) {
            byEventId.put(eventId, id);
        }
        records.put(id, new DispatchRecord(id, eventId, typeOf(notification), notification.recipient(),
                notification.subject(), notification.body(), "PENDING", 0, 0, null,
                Instant.EPOCH, null, List.of()));
        return Optional.of(id);
    }

    @Override
    public synchronized void complete(long journalId, List<ChannelResult> results) {
        DispatchRecord existing = records.get(journalId);
        if (existing == null) {
            return;
        }
        long succeeded = results.stream().filter(ChannelResult.Success.class::isInstance).count();
        records.put(journalId, new DispatchRecord(existing.id(), existing.eventId(), existing.type(),
                existing.recipient(), existing.subject(), existing.body(),
                statusOf(results.size(), succeeded), results.size(), (int) succeeded, existing.resentFromId(),
                existing.createdAt(), Instant.EPOCH,
                results.stream().map(FakeNotificationJournal::outcome).toList()));
    }

    @Override
    public synchronized void linkResend(String eventId, long originalId) {
        Long id = byEventId.get(eventId);
        if (id == null) {
            return;
        }
        DispatchRecord existing = records.get(id);
        if (existing.resentFromId() != null) {
            return;
        }
        records.put(id, new DispatchRecord(existing.id(), existing.eventId(), existing.type(), existing.recipient(),
                existing.subject(), existing.body(), existing.status(), existing.channelsTotal(),
                existing.channelsSucceeded(), originalId, existing.createdAt(), existing.completedAt(),
                existing.channels()));
    }

    @Override
    public synchronized List<DispatchRecord> findRecent(String status, String recipient, int limit, int offset) {
        return records.values().stream()
                .filter(r -> status == null || status.isBlank()
                        || r.status().equals(status.toUpperCase(Locale.ROOT)))
                .filter(r -> recipient == null || recipient.isBlank() || recipient.equals(r.recipient()))
                .skip(Math.max(offset, 0))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Optional<DispatchRecord> findById(long id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized long count(String status, String recipient) {
        return findRecent(status, recipient, Integer.MAX_VALUE, 0).size();
    }

    private static DispatchRecord.ChannelOutcome outcome(ChannelResult result) {
        return switch (result) {
            case ChannelResult.Success s ->
                    new DispatchRecord.ChannelOutcome(s.channel(), "SUCCESS", s.attempts(), null, Instant.EPOCH);
            case ChannelResult.Failure f ->
                    new DispatchRecord.ChannelOutcome(f.channel(), "FAILURE", f.attempts(), f.error(), Instant.EPOCH);
        };
    }

    private static String statusOf(int total, long succeeded) {
        if (total == 0) {
            return "NO_CHANNEL";
        }
        if (succeeded == total) {
            return "DELIVERED";
        }
        return succeeded == 0 ? "FAILED" : "PARTIAL";
    }

    private static String typeOf(Notification notification) {
        return notification.type() == null ? "GENERIC" : notification.type().name();
    }
}
