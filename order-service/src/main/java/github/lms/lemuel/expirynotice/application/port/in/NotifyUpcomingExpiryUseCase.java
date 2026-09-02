package github.lms.lemuel.expirynotice.application.port.in;

import java.time.OffsetDateTime;

/** 만료 예고 통보. */
public interface NotifyUpcomingExpiryUseCase {

    /**
     * @param asOf     기준 시각. 재실행은 과거 시각을 넣어 그 날 나갔어야 할 통보를 채운다
     * @param dryRun   true 면 원장에 적지도 이벤트를 내지도 않고 대상 건수만 센다
     * @param limit    단계·대상당 조회 상한. 첫 도입 때 수십만 건이 한 번에 나가는 것을 막는다
     */
    NotifyExpiryResult notify(OffsetDateTime asOf, boolean dryRun, int limit);

    /**
     * @param notified 이번에 새로 통보한 건수
     * @param skipped  이미 통보돼 있어 건너뛴 건수 — 배치가 매일 도는 한 정상값이다
     * @param failed   발행 중 실패한 건수
     */
    record NotifyExpiryResult(int notified, int skipped, int failed) {

        public static NotifyExpiryResult empty() {
            return new NotifyExpiryResult(0, 0, 0);
        }

        public NotifyExpiryResult plus(NotifyExpiryResult other) {
            return new NotifyExpiryResult(notified + other.notified,
                    skipped + other.skipped, failed + other.failed);
        }
    }
}
