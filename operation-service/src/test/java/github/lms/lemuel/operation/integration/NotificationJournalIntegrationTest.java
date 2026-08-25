package github.lms.lemuel.operation.integration;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.notification.application.ChannelResult;
import github.lms.lemuel.operation.notification.application.DispatchRecord;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournal;
import github.lms.lemuel.operation.notification.application.port.out.NotificationJournalQuery;
import github.lms.lemuel.operation.notification.domain.Notification;
import github.lms.lemuel.operation.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 저널의 <b>내구 멱등</b> 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway.
 *
 * <p>여기서 실 DB 를 쓰는 이유는 검증 대상이 애플리케이션 코드가 아니라 <b>UNIQUE 인덱스</b>이기
 * 때문이다. 목이나 인메모리 대역으로 바꾸면 "ON CONFLICT DO NOTHING 이 정말 한 쪽만 통과시키는가"
 * 라는 질문 자체가 사라지고, 그 질문이 이 슬라이스의 존재 이유다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class NotificationJournalIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("operation_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    NotificationJournal journal;
    @Autowired
    NotificationJournalQuery journalQuery;
    @Autowired
    JdbcTemplate jdbc;

    private static Notification sample(String eventId) {
        return new Notification(NotificationType.SETTLEMENT_CONFIRMED, "ops@lemuel.co.kr",
                "정산 확정", "본문", eventId);
    }

    @Test
    @DisplayName("같은 eventId 는 두 번째 begin 에서 막힌다 — 이것이 L2 멱등이다")
    void sameEventIdOpensOnce() {
        String eventId = "evt-" + UUID.randomUUID();

        Optional<Long> first = journal.begin(sample(eventId));
        Optional<Long> second = journal.begin(sample(eventId));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("레플리카 2개가 같은 순간에 들어와도 한 쪽만 통과한다")
    void concurrentBeginLetsExactlyOneThrough() throws Exception {
        String eventId = "evt-race-" + UUID.randomUUID();
        int racers = 8;
        // 배리어로 진짜 동시에 출발시킨다 — 순차로 부르면 인덱스가 아니라 시간이 막아 주고,
        // 그러면 이 테스트는 아무것도 증명하지 못한다.
        CyclicBarrier startLine = new CyclicBarrier(racers);

        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, racers)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        startLine.await();
                        return journal.begin(sample(eventId)).isPresent();
                    })
                    .toList();

            long winners = pool.invokeAll(attempts).stream()
                    .map(NotificationJournalIntegrationTest::valueOf)
                    .filter(Boolean.TRUE::equals)
                    .count();

            assertThat(winners).isOne();
        }
    }

    @Test
    @DisplayName("eventId 가 없는 발송은 멱등 대상이 아니다 — 매번 새 항목이 열린다")
    void nullEventIdIsNeverDeduped() {
        Notification manual = new Notification(NotificationType.GENERIC, "ops@lemuel.co.kr", "수기", "본문", null);

        assertThat(journal.begin(manual)).isPresent();
        assertThat(journal.begin(manual)).isPresent();
    }

    @Test
    @DisplayName("complete 는 채널별 결과를 남기고 부분 성공을 PARTIAL 로 닫는다")
    void completeRecordsChannelsAndPartialStatus() {
        String eventId = "evt-partial-" + UUID.randomUUID();
        long id = journal.begin(sample(eventId)).orElseThrow();

        journal.complete(id, List.of(
                new ChannelResult.Success("log", 1),
                new ChannelResult.Failure("slack", 3, "timeout after 3000ms")));

        DispatchRecord record = journalQuery.findById(id).orElseThrow();
        assertThat(record.status()).isEqualTo("PARTIAL");
        assertThat(record.channelsTotal()).isEqualTo(2);
        assertThat(record.channelsSucceeded()).isEqualTo(1);
        assertThat(record.completedAt()).isNotNull();
        assertThat(record.channels()).extracting(DispatchRecord.ChannelOutcome::channel, c -> c.status())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("log", "SUCCESS"),
                        org.assertj.core.groups.Tuple.tuple("slack", "FAILURE"));
        assertThat(record.channels()).filteredOn(c -> "slack".equals(c.channel()))
                .singleElement()
                .extracting(DispatchRecord.ChannelOutcome::error)
                .isEqualTo("timeout after 3000ms");
    }

    @Test
    @DisplayName("활성 채널 0개는 NO_CHANNEL — 실패가 아니라 배포 설정 오류로 구분된다")
    void emptyResultsCloseAsNoChannel() {
        long id = journal.begin(sample("evt-nochannel-" + UUID.randomUUID())).orElseThrow();

        journal.complete(id, List.of());

        assertThat(journalQuery.findById(id).orElseThrow().status()).isEqualTo("NO_CHANNEL");
    }

    @Test
    @DisplayName("complete 를 두 번 불러도 결과가 같다 — 채널 행이 늘어나지 않는다")
    void completeIsIdempotent() {
        long id = journal.begin(sample("evt-twice-" + UUID.randomUUID())).orElseThrow();
        List<ChannelResult> results = List.of(new ChannelResult.Success("log", 1));

        journal.complete(id, results);
        journal.complete(id, results);

        assertThat(journalQuery.findById(id).orElseThrow().channels()).hasSize(1);
    }

    @Test
    @DisplayName("linkResend 는 계보를 붙이되 이미 붙은 것을 덮어쓰지 않는다")
    void linkResendDoesNotOverwriteExistingLineage() {
        long original = journal.begin(sample("evt-orig-" + UUID.randomUUID())).orElseThrow();
        long other = journal.begin(sample("evt-other-" + UUID.randomUUID())).orElseThrow();
        String resendEventId = "resend:%d:%s".formatted(original, UUID.randomUUID());
        long resent = journal.begin(sample(resendEventId)).orElseThrow();

        journal.linkResend(resendEventId, original);
        journal.linkResend(resendEventId, other); // 두 번째는 무시돼야 한다

        assertThat(journalQuery.findById(resent).orElseThrow().resentFromId()).isEqualTo(original);
    }

    @Test
    @DisplayName("상태·수신자 필터가 목록과 총계에 같이 걸린다")
    void listFiltersByStatusAndRecipient() {
        String recipient = "filter-%s@lemuel.co.kr".formatted(UUID.randomUUID());
        long delivered = journal.begin(new Notification(NotificationType.GENERIC, recipient, "제목", "본문",
                "evt-f1-" + UUID.randomUUID())).orElseThrow();
        long failed = journal.begin(new Notification(NotificationType.GENERIC, recipient, "제목", "본문",
                "evt-f2-" + UUID.randomUUID())).orElseThrow();
        journal.complete(delivered, List.of(new ChannelResult.Success("log", 1)));
        journal.complete(failed, List.of(new ChannelResult.Failure("log", 3, "boom")));

        assertThat(journalQuery.count(null, recipient)).isEqualTo(2);
        assertThat(journalQuery.count("FAILED", recipient)).isEqualTo(1);
        assertThat(journalQuery.findRecent("FAILED", recipient, 10, 0))
                .singleElement()
                .extracting(DispatchRecord::id)
                .isEqualTo(failed);
    }

    @Test
    @DisplayName("목록 조회는 본문 조회 비용을 줄이려 채널을 채우지 않는다")
    void listOmitsChannelDetail() {
        String recipient = "list-%s@lemuel.co.kr".formatted(UUID.randomUUID());
        long id = journal.begin(new Notification(NotificationType.GENERIC, recipient, "제목", "본문",
                "evt-list-" + UUID.randomUUID())).orElseThrow();
        journal.complete(id, List.of(new ChannelResult.Success("log", 1)));

        assertThat(journalQuery.findRecent(null, recipient, 10, 0))
                .singleElement()
                .satisfies(record -> assertThat(record.channels()).isEmpty());
    }

    @Test
    @DisplayName("prune 은 보존기간 초과분만 지우고 자식 행까지 함께 지운다")
    void pruneDeletesExpiredWithChildren() {
        String eventId = "evt-prune-" + UUID.randomUUID();
        long id = journal.begin(sample(eventId)).orElseThrow();
        journal.complete(id, List.of(new ChannelResult.Success("log", 1)));
        // 40일 전으로 밀어 놓는다 — 테스트가 시계를 기다리지 않게.
        jdbc.update("UPDATE opslab.notification_dispatches SET created_at = NOW() - INTERVAL '40 days' WHERE id = ?",
                id);
        long fresh = journal.begin(sample("evt-fresh-" + UUID.randomUUID())).orElseThrow();

        Long deleted = jdbc.queryForObject(
                "SELECT opslab.prune_notification_dispatches(INTERVAL '30 days')", Long.class);

        assertThat(deleted).isGreaterThanOrEqualTo(1L);
        assertThat(journalQuery.findById(id)).isEmpty();
        assertThat(journalQuery.findById(fresh)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM opslab.notification_dispatch_channels WHERE dispatch_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    @DisplayName("prune 은 음수 보존기간을 거부한다 — 오타 한 번이 전건 삭제가 되지 않게")
    void pruneRejectsNegativeRetention() {
        // 던졌다는 것만 보면 안 된다. 함수가 아예 깨져 있어도(예: 본문이 opslab 을 못 찾아
        // "relation does not exist") 똑같이 던지므로 통과해 버린다 — 실제로 그렇게 이 게이트가
        // 결함을 놓쳤다. 그래서 **우리가 의도한 그 거부인지**를 메시지로 못박는다.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.queryForObject(
                "SELECT opslab.prune_notification_dispatches(INTERVAL '-1 days')", Long.class)))
                .isNotNull()
                .hasMessageContaining("p_retention");
    }

    private static Boolean valueOf(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Boolean.FALSE;
        } catch (Exception e) {
            return Boolean.FALSE;
        }
    }
}
