package github.lms.lemuel.operation.notification.adapter.out.stream;

import github.lms.lemuel.operation.notification.application.port.out.NotificationStream;
import github.lms.lemuel.operation.notification.application.port.out.StreamListener;
import github.lms.lemuel.operation.notification.application.port.out.StreamSubscription;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * {@link NotificationStream} 포트의 인메모리 구현 — 아웃바운드 어댑터라 애플리케이션 계층은
 * 구현을 모른다.
 *
 * <p>설계:
 * <ul>
 *   <li><b>락 하나</b>가 시퀀스·수신자별 보존 버퍼·구독자 인덱스를 함께 지킨다. 리스너 호출(SSE 쓰기)은
 *       락 <b>바깥</b>에서 일어나므로, 멈춰 있는 브라우저가 발행자를 막을 수 없다.</li>
 *   <li><b>구독자별 메일박스 + 비재진입 드레인 플래그</b>가 구독자마다 엄격한 순서 전달을 보장한다.
 *       플래그를 먼저 잡은 쪽이 메일박스를 비우고 나머지는 넣기만 한다. 전달받는 도중에 발행하는
 *       리스너(재진입)는 이미 흐르는 이벤트 뒤에 줄을 서지, 새치기하지 않는다.</li>
 *   <li><b>보존은 유한하다</b> — 수신자별(재생 창) + 수신자 수 전역(유휴 항목 정리). 항상 떠 있는
 *       프로세스가 무한히 자라지 않는다.</li>
 * </ul>
 *
 * <p>MVP 로서 의도된 한계: 상태가 프로세스에 있다. 재시작하면 재생 창이 사라지고, 레플리카가 둘
 * 이상이면 클라이언트는 <b>재접속한 레플리카가 가진 것만</b> 이어받는다(docs/sse.md).
 * operation-service 는 단일 인스턴스 관제 서비스라 이 전제가 자연스럽다.
 */
public class InMemoryNotificationStream implements NotificationStream {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotificationStream.class);

    private static final int DEFAULT_BUFFER_PER_RECIPIENT = 100;
    private static final int DEFAULT_MAX_RECIPIENTS = 10_000;
    private static final int DEFAULT_MAX_PENDING_PER_SUBSCRIBER = 200;

    private final int bufferPerRecipient;
    private final int maxRecipients;
    private final int maxPendingPerSubscriber;
    private final Supplier<Instant> clock;

    private final ReentrantLock lock = new ReentrantLock();

    /** lock 이 지킨다. */
    private long seq = 0L;

    /**
     * recipient → 보존 이벤트(오래된 것부터). 마지막 발행 순서를 유지하는 LinkedHashMap 이라
     * 정리는 앞(가장 식은 것)에서 시작하면 된다. lock 이 지킨다.
     */
    private final LinkedHashMap<String, Deque<StreamEvent>> buffers = new LinkedHashMap<>();

    /** recipient → 그 신원으로 듣는 구독자들. lock 이 지킨다. */
    private final Map<String, Set<Subscriber>> byRecipient = new HashMap<>();

    /** lock 이 지킨다. */
    private final Set<Subscriber> subscribers = new LinkedHashSet<>();

    public InMemoryNotificationStream() {
        this(DEFAULT_BUFFER_PER_RECIPIENT, DEFAULT_MAX_RECIPIENTS, DEFAULT_MAX_PENDING_PER_SUBSCRIBER, Instant::now);
    }

    public InMemoryNotificationStream(int bufferPerRecipient, int maxRecipients, int maxPendingPerSubscriber) {
        this(bufferPerRecipient, maxRecipients, maxPendingPerSubscriber, Instant::now);
    }

    public InMemoryNotificationStream(int bufferPerRecipient,
                                      int maxRecipients,
                                      int maxPendingPerSubscriber,
                                      Supplier<Instant> clock) {
        this.bufferPerRecipient = bufferPerRecipient;
        this.maxRecipients = maxRecipients;
        this.maxPendingPerSubscriber = maxPendingPerSubscriber;
        this.clock = clock;
    }

    @Override
    public StreamEvent publish(Notification notification) {
        StreamEvent event;
        List<Subscriber> targets;
        lock.lock();
        try {
            event = new StreamEvent(++seq, notification, clock.get());
            retainLocked(event);
            targets = new ArrayList<>(byRecipient.getOrDefault(notification.recipient(), Set.of()));
            for (Subscriber target : targets) {
                enqueueLocked(target, event);
            }
        } finally {
            lock.unlock();
        }
        // 전달은 락 밖에서. 구독자마다 순서대로 드레인된다.
        targets.forEach(this::pump);
        return event;
    }

    @Override
    public StreamSubscription subscribe(Set<String> recipients, Long lastEventId, StreamListener listener) {
        if (recipients == null || recipients.isEmpty()) {
            // 빈 신원 집합은 "아무것도 구독하지 않음"이다 — 영원히 조용히 아무것도 안 주느니
            // 여기서 실패하는 편이 낫다.
            throw new IllegalArgumentException("at least one recipient identity is required");
        }

        Subscriber subscriber = new Subscriber(Set.copyOf(recipients), listener);
        lock.lock();
        try {
            // 등록과 백로그 적재를 <b>한 락 안에서</b> 하는 것이 무결점 재개의 핵심이다:
            // 동시에 발행된 이벤트는 백로그 스냅샷에 들어가거나 그 뒤 메일박스에 들어가거나
            // 둘 중 하나지, 양쪽에 들어가지도 양쪽에서 빠지지도 않는다.
            subscribers.add(subscriber);
            for (String recipient : subscriber.recipients) {
                byRecipient.computeIfAbsent(recipient, key -> new LinkedHashSet<>()).add(subscriber);
            }
            if (lastEventId != null) {
                subscriber.recipients.stream()
                        .flatMap(recipient -> buffers.getOrDefault(recipient, new ArrayDeque<>()).stream())
                        .filter(event -> event.seq() > lastEventId)
                        .sorted(Comparator.comparingLong(StreamEvent::seq))
                        .forEach(event -> enqueueLocked(subscriber, event));
            }
        } finally {
            lock.unlock();
        }
        pump(subscriber);
        return new Subscription(subscriber);
    }

    @Override
    public int subscriberCount() {
        lock.lock();
        try {
            return subscribers.size();
        } finally {
            lock.unlock();
        }
    }

    // --- 보존 -----------------------------------------------------------------

    /** 호출자가 lock 을 쥔다. */
    private void retainLocked(StreamEvent event) {
        // remove+put 으로 LinkedHashMap 을 마지막 발행 순서로 유지한다 — 앞쪽이 가장 식은 항목.
        Deque<StreamEvent> buffer = buffers.remove(event.recipient());
        if (buffer == null) {
            buffer = new ArrayDeque<>(bufferPerRecipient);
        }
        buffer.addLast(event);
        while (buffer.size() > bufferPerRecipient) {
            buffer.removeFirst();
        }
        buffers.put(event.recipient(), buffer);
        pruneLocked();
    }

    /**
     * 추적 중인 수신자가 너무 많아지면 가장 식은 보존 버퍼를 버린다. <b>살아 있는 구독자가 있는
     * 수신자는 절대 버리지 않는다</b> — 그 재개 창은 유효해야 한다. 호출자가 lock 을 쥔다.
     */
    private void pruneLocked() {
        if (buffers.size() <= maxRecipients) {
            return;
        }
        List<String> victims = buffers.keySet().stream()
                .filter(recipient -> byRecipient.getOrDefault(recipient, Set.of()).isEmpty())
                .limit((long) buffers.size() - maxRecipients)
                .toList();
        victims.forEach(buffers::remove);
        if (!victims.isEmpty()) {
            log.debug("pruned {} idle retention buffers", victims.size());
        }
    }

    // --- 전달 -----------------------------------------------------------------

    /** 호출자가 lock 을 쥔다. */
    private void enqueueLocked(Subscriber subscriber, StreamEvent event) {
        subscriber.mailbox.addLast(event);
        while (subscriber.mailbox.size() > maxPendingPerSubscriber) {
            StreamEvent dropped = subscriber.mailbox.removeFirst();
            log.warn("subscriber mailbox full ({}), dropped seq={} — the client will see an id gap",
                    maxPendingPerSubscriber, dropped.seq());
        }
    }

    /**
     * 구독자의 메일박스를 시퀀스 순서로 비운다. 드레인 플래그를 일부러 <b>재진입 불가</b>한 평범한
     * CAS 로 둔다: 전달받는 도중에 리스너가 발행하면, 새 이벤트는 이미 흐르는 것들 뒤에 줄을 서야지
     * 중첩 전달되어 순서를 앞지르면 안 된다.
     */
    private void pump(Subscriber subscriber) {
        while (true) {
            if (!subscriber.draining.compareAndSet(false, true)) {
                return;
            }
            try {
                while (true) {
                    StreamEvent event = pollLocked(subscriber);
                    if (event == null || !deliver(subscriber, event)) {
                        break;
                    }
                }
            } finally {
                subscriber.draining.set(false);
            }
            // 마지막 폴링과 플래그 해제 사이에 뭔가 들어왔을 수 있다.
            if (isMailboxEmpty(subscriber)) {
                return;
            }
        }
    }

    private StreamEvent pollLocked(Subscriber subscriber) {
        lock.lock();
        try {
            return subscriber.mailbox.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    private boolean isMailboxEmpty(Subscriber subscriber) {
        lock.lock();
        try {
            return subscriber.mailbox.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /** @return 구독자가 사라졌으면(취소·실패) false. */
    private boolean deliver(Subscriber subscriber, StreamEvent event) {
        if (subscriber.cancelled) {
            return false;
        }
        try {
            subscriber.listener.onEvent(event);
            return true;
        } catch (Exception e) {
            // 쓰기 실패는 클라이언트가 사라졌다는 뜻이다(닫힌 탭·죽은 프록시).
            // 떨궈낸다 — 죽은 구독자 하나가 발행 자체를 실패시키게 두지 않는다.
            log.info("dropping subscriber after delivery failure: {}", e.toString());
            remove(subscriber);
            return false;
        }
    }

    private void remove(Subscriber subscriber) {
        lock.lock();
        try {
            subscriber.cancelled = true;
            subscribers.remove(subscriber);
            for (String recipient : subscriber.recipients) {
                Set<Subscriber> subs = byRecipient.get(recipient);
                if (subs == null) {
                    continue;
                }
                subs.remove(subscriber);
                if (subs.isEmpty()) {
                    byRecipient.remove(recipient);
                }
            }
            subscriber.mailbox.clear();
        } finally {
            lock.unlock();
        }
    }

    private static final class Subscriber {
        private final Set<String> recipients;
        private final StreamListener listener;
        /** 스트림 락이 지킨다. */
        private final Deque<StreamEvent> mailbox = new ArrayDeque<>();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private volatile boolean cancelled = false;

        private Subscriber(Set<String> recipients, StreamListener listener) {
            this.recipients = recipients;
            this.listener = listener;
        }
    }

    private final class Subscription implements StreamSubscription {
        private final Subscriber subscriber;

        private Subscription(Subscriber subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void cancel() {
            remove(subscriber);
        }
    }
}
