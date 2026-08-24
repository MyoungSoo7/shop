package github.lms.lemuel.operation.notification.application.service;

import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchResult;
import github.lms.lemuel.operation.notification.application.port.in.DispatchNotificationUseCase;
import github.lms.lemuel.operation.notification.application.port.out.DedupeStore;
import github.lms.lemuel.operation.notification.application.port.out.NotificationChannel;
import github.lms.lemuel.operation.notification.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 이 슬라이스의 심장. {@link Notification} 을 활성 채널 전체로 <b>동시에</b> 팬아웃한다.
 * 채널마다 독립적으로 타임아웃 + 유한 재시도/백오프를 두르므로, 느리거나 실패하는 채널 하나가
 * 나머지를 막거나 실패시키지 못한다. 결과는 합산된다.
 *
 * <p><b>동시성 모델</b>: 이관 전 Kotlin 구현은 코루틴({@code async}/{@code awaitAll} +
 * {@code withTimeout})이었다. Java 25 로 옮기면서 가상 스레드 executor 로 바꿨다 — 채널당 1
 * 가상 스레드, 시도당 1 가상 스레드(타임아웃 감시용)라 플랫폼 스레드를 잡아먹지 않는다.
 * 시도 스레드를 따로 두는 이유는 {@code Future.get(timeout)} 만이 <b>블로킹 채널 구현</b>(SMTP
 * 소켓 등)에 상한을 씌울 수 있기 때문이다. 타임아웃 시 {@code cancel(true)} 로 인터럽트를 건다.
 *
 * <p>멱등은 맨 앞의 {@link DedupeStore} 가 담당한다 — 재전달된 eventId 는 스킵된다.
 *
 * <p>{@link AutoCloseable} 이라 스프링이 빈 소멸 시 executor 를 닫는다(@Bean 의 추론된 destroyMethod).
 */
public class NotificationDispatcher implements DispatchNotificationUseCase, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private static final long DEFAULT_TIMEOUT_MS = 3_000L;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BACKOFF_MS = 50L;

    private final List<NotificationChannel> channels;
    private final DedupeStore dedupe;
    private final long perChannelTimeoutMs;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final ExecutorService executor;

    public NotificationDispatcher(List<NotificationChannel> channels, DedupeStore dedupe) {
        this(channels, dedupe, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS);
    }

    public NotificationDispatcher(List<NotificationChannel> channels,
                                  DedupeStore dedupe,
                                  long perChannelTimeoutMs,
                                  int maxAttempts,
                                  long baseBackoffMs) {
        this.channels = List.copyOf(channels);
        this.dedupe = dedupe;
        this.perChannelTimeoutMs = perChannelTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public DispatchResult dispatch(Notification notification) {
        // --- 멱등 게이트 ---
        String eventId = notification.eventId();
        if (eventId != null && !dedupe.markIfFirst(eventId)) {
            log.info("dedupe skip eventId={} type={}", eventId, notification.type());
            return DispatchResult.skipped();
        }

        List<NotificationChannel> enabled = channels.stream()
                .filter(NotificationChannel::isEnabled)
                .toList();
        if (enabled.isEmpty()) {
            log.warn("no enabled channels; nothing dispatched type={}", notification.type());
            return new DispatchResult(false, List.of());
        }

        // --- 동시 팬아웃 ---
        List<Future<ChannelResult>> futures = enabled.stream()
                .map(channel -> executor.<ChannelResult>submit(() -> sendWithResilience(channel, notification)))
                .toList();

        List<ChannelResult> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            results.add(awaitChannel(futures.get(i), enabled.get(i)));
        }

        log.info("dispatched type={} recipient={} channels={} success={} failure={}",
                notification.type(), notification.recipient(), enabled.size(),
                results.stream().filter(ChannelResult.Success.class::isInstance).count(),
                results.stream().filter(ChannelResult.Failure.class::isInstance).count());
        return new DispatchResult(false, results);
    }

    /**
     * 채널 작업의 결과를 회수한다. 재시도 루프가 예외를 삼키므로 여기까지 예외가 올라오는 것은
     * 채널 로직이 아니라 executor 자체의 이상(거부·인터럽트)이며, 그것도 결과 객체로 환원해
     * 한 채널의 사고가 팬아웃 전체를 무너뜨리지 않게 한다.
     */
    private ChannelResult awaitChannel(Future<ChannelResult> future, NotificationChannel channel) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ChannelResult.Failure(channel.name(), maxAttempts, "interrupted");
        } catch (ExecutionException e) {
            return new ChannelResult.Failure(channel.name(), maxAttempts, describe(e.getCause()));
        }
    }

    /** 지수 백오프 재시도. 시도 하나하나에 채널별 타임아웃 상한이 걸린다. */
    private ChannelResult sendWithResilience(NotificationChannel channel, Notification notification) {
        String lastError = "unknown";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                sendWithTimeout(channel, notification);
                return new ChannelResult.Success(channel.name(), attempt);
            } catch (InterruptedException e) {
                // 바깥에서 온 진짜 인터럽트 — 삼키지 않고 즉시 빠져나간다.
                Thread.currentThread().interrupt();
                return new ChannelResult.Failure(channel.name(), attempt, "interrupted");
            } catch (TimeoutException e) {
                lastError = "timeout after %dms".formatted(perChannelTimeoutMs);
                log.warn("channel={} attempt={}/{} {}", channel.name(), attempt, maxAttempts, lastError);
            } catch (Exception e) {
                lastError = describe(e);
                log.warn("channel={} attempt={}/{} failed: {}", channel.name(), attempt, maxAttempts, lastError);
            }
            if (attempt < maxAttempts && !backoff(attempt)) {
                return new ChannelResult.Failure(channel.name(), attempt, "interrupted");
            }
        }
        return new ChannelResult.Failure(channel.name(), maxAttempts, lastError);
    }

    /**
     * 한 번의 전송 시도에 상한을 씌운다. 블로킹 채널(SMTP·HTTP)을 중단시킬 방법은
     * 별도 스레드 + {@code cancel(true)} 인터럽트뿐이다.
     */
    private void sendWithTimeout(NotificationChannel channel, Notification notification) throws Exception {
        Future<?> attempt = executor.submit(() -> {
            channel.send(notification);
            return null;
        });
        try {
            attempt.get(perChannelTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            attempt.cancel(true);
            throw e;
        }
    }

    /** @return 정상적으로 대기했으면 true, 인터럽트되면 false. */
    private boolean backoff(int attempt) {
        try {
            Thread.sleep(baseBackoffMs * (1L << (attempt - 1))); // 50, 100, 200...
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 메시지가 없는 예외(BrokenBarrierException 등)는 타입명이라도 남긴다 — 사유 없는 실패를 만들지 않는다. */
    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String message = t.getMessage();
        return (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
