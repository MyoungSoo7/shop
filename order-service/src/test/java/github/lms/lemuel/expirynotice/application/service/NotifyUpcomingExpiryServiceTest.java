package github.lms.lemuel.expirynotice.application.service;

import github.lms.lemuel.expirynotice.application.port.in.NotifyUpcomingExpiryUseCase.NotifyExpiryResult;
import github.lms.lemuel.expirynotice.application.port.out.LoadExpiringItemsPort;
import github.lms.lemuel.expirynotice.application.port.out.PublishExpiryNoticeEventPort;
import github.lms.lemuel.expirynotice.application.port.out.RecordExpiryNoticePort;
import github.lms.lemuel.expirynotice.domain.ExpiringItem;
import github.lms.lemuel.expirynotice.domain.ExpiryNoticeStage;
import github.lms.lemuel.expirynotice.domain.ExpirySubject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료 예고 통보의 계약.
 *
 * <p>여기서 지키려는 것은 <b>사용자에게 스팸을 보내지 않는 것</b>과 <b>한 사람 때문에 나머지가
 * 통보를 못 받지 않는 것</b>이다. 둘 다 어겨도 컴파일은 통과하고 정상 경로는 초록이다.
 */
class NotifyUpcomingExpiryServiceTest {

    private static final OffsetDateTime AS_OF = OffsetDateTime.of(2026, 9, 3, 3, 10, 0, 0, ZoneOffset.ofHours(9));

    /** 조회된 창을 그대로 기록하는 최소 구현. 목이 아니라 진짜 객체라 창 계산이 눈에 보인다. */
    private static class RecordingLoadPort implements LoadExpiringItemsPort {
        record Window(ExpirySubject subject, OffsetDateTime floor, OffsetDateTime ceiling, int limit) { }

        final List<Window> windows = new ArrayList<>();
        List<ExpiringItem> answer = List.of();

        @Override
        public List<ExpiringItem> findExpiringBetween(ExpirySubject subject, OffsetDateTime floorInclusive,
                                                      OffsetDateTime ceilingExclusive, int limit) {
            windows.add(new Window(subject, floorInclusive, ceilingExclusive, limit));
            return subject == ExpirySubject.POINT_LOT ? answer : List.of();
        }
    }

    private static class FakeNoticeLedger implements RecordExpiryNoticePort {
        final Set<String> claimed = new LinkedHashSet<>();

        @Override
        public boolean claim(ExpiringItem item, ExpiryNoticeStage stage) {
            return claimed.add(item.subject() + ":" + item.subjectId() + ":" + stage);
        }
    }

    private static class RecordingPublisher implements PublishExpiryNoticeEventPort {
        final List<Long> published = new ArrayList<>();
        Long explodeOn;

        @Override
        public void expiryUpcoming(ExpiringItem item, ExpiryNoticeStage stage) {
            if (explodeOn != null && explodeOn == item.subjectId()) {
                throw new IllegalStateException("발행 실패(테스트가 의도한 고장)");
            }
            published.add(item.subjectId());
        }
    }

    private final RecordingLoadPort loadPort = new RecordingLoadPort();
    private final FakeNoticeLedger ledger = new FakeNoticeLedger();
    private final RecordingPublisher publisher = new RecordingPublisher();

    private NotifyUpcomingExpiryService service() {
        return new NotifyUpcomingExpiryService(loadPort, new ExpiryNoticeEmitter(ledger, publisher));
    }

    private static ExpiringItem lot(long id) {
        return new ExpiringItem(ExpirySubject.POINT_LOT, id, 100L + id,
                new BigDecimal("1000"), AS_OF.plusDays(5), null);
    }

    @Test
    @DisplayName("단계별 창은 이어 붙었을 때 겹치지도 비지도 않는다 — 겹치면 하루에 두 통이 나간다")
    void 단계_창은_연속이고_겹치지_않는다() {
        service().notify(AS_OF, false, 500);

        List<RecordingLoadPort.Window> pointWindows = loadPort.windows.stream()
                .filter(w -> w.subject() == ExpirySubject.POINT_LOT)
                .toList();
        assertThat(pointWindows).hasSize(ExpiryNoticeStage.values().length);

        // D30 = [asOf+7d, asOf+30d), D7 = [asOf+1d, asOf+7d), D1 = [asOf, asOf+1d)
        assertThat(pointWindows.get(0).floor()).isEqualTo(AS_OF.plusDays(7));
        assertThat(pointWindows.get(0).ceiling()).isEqualTo(AS_OF.plusDays(30));
        assertThat(pointWindows.get(1).floor()).isEqualTo(AS_OF.plusDays(1));
        assertThat(pointWindows.get(1).ceiling()).isEqualTo(AS_OF.plusDays(7));
        assertThat(pointWindows.get(2).floor()).isEqualTo(AS_OF);
        assertThat(pointWindows.get(2).ceiling()).isEqualTo(AS_OF.plusDays(1));

        // 앞 단계의 하한 == 뒤 단계의 상한. 이 등식이 깨지는 순간 틈이나 중복이 생긴다.
        assertThat(pointWindows.get(0).floor()).isEqualTo(pointWindows.get(1).ceiling());
        assertThat(pointWindows.get(1).floor()).isEqualTo(pointWindows.get(2).ceiling());
    }

    @Test
    @DisplayName("세 대상을 모두 훑는다 — 하나라도 빠지면 그 종류만 조용히 통보가 없다")
    void 세_대상을_모두_훑는다() {
        service().notify(AS_OF, false, 500);

        assertThat(loadPort.windows).extracting(RecordingLoadPort.Window::subject)
                .containsAll(EnumSet.allOf(ExpirySubject.class));
    }

    @Test
    @DisplayName("같은 대상·단계는 두 번 나가지 않는다 — 매일 도는 배치가 매일 같은 문자를 보내면 안 된다")
    void 이미_보낸_건은_건너뛴다() {
        loadPort.answer = List.of(lot(1), lot(2));

        NotifyExpiryResult first = service().notify(AS_OF, false, 500);
        NotifyExpiryResult second = service().notify(AS_OF, false, 500);

        // 단계 3개 × 2건 = 6. 첫 실행은 전부 신규, 둘째 실행은 전부 기통보.
        assertThat(first.notified()).isEqualTo(6);
        assertThat(second.notified()).isZero();
        assertThat(second.skipped()).isEqualTo(6);
        assertThat(publisher.published).hasSize(6);
    }

    @Test
    @DisplayName("dry-run 은 원장을 선점하지 않는다 — 선점하면 진짜 실행이 전부 스킵된다")
    void dryRun_은_원장을_건드리지_않는다() {
        loadPort.answer = List.of(lot(1));

        NotifyExpiryResult dry = service().notify(AS_OF, true, 500);

        assertThat(dry.notified()).isZero();
        assertThat(ledger.claimed).isEmpty();
        assertThat(publisher.published).isEmpty();

        // dry-run 뒤에 실제 실행이 정상적으로 나가야 한다.
        assertThat(service().notify(AS_OF, false, 500).notified()).isEqualTo(3);
    }

    @Test
    @DisplayName("한 건의 발행 실패가 나머지를 막지 않는다")
    void 한_건_실패는_나머지를_막지_않는다() {
        loadPort.answer = List.of(lot(1), lot(2), lot(3));
        publisher.explodeOn = 2L;

        NotifyExpiryResult result = service().notify(AS_OF, false, 500);

        // 단계 3개 × 3건 = 9 중 2번 로트의 3건만 실패한다.
        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.notified()).isEqualTo(6);
        assertThat(publisher.published).containsExactlyInAnyOrder(1L, 3L, 1L, 3L, 1L, 3L);
    }

    @Test
    @DisplayName("조회 상한은 그대로 포트에 전달된다 — 첫 도입 때 수십만 건이 한 번에 나가면 안 된다")
    void 상한_전달() {
        service().notify(AS_OF, false, 42);

        assertThat(loadPort.windows).allSatisfy(w -> assertThat(w.limit()).isEqualTo(42));
    }

    @Test
    @DisplayName("단계 상수의 창 폭이 실제로 30·7·1일을 덮는다")
    void 단계_창_폭() {
        assertThat(ExpiryNoticeStage.D30.leadTime()).isEqualTo(Duration.ofDays(30));
        assertThat(ExpiryNoticeStage.D7.leadTime()).isEqualTo(Duration.ofDays(7));
        assertThat(ExpiryNoticeStage.D1.leadTime()).isEqualTo(Duration.ofDays(1));
        // 마지막 단계의 하한은 기준시각 자신이다 — 여기가 0 이 아니면 만료 직전 구간에 틈이 생긴다.
        assertThat(ExpiryNoticeStage.D1.floorLeadTime()).isEqualTo(Duration.ZERO);
    }
}
