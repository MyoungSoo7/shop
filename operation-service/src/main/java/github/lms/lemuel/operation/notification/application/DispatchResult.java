package github.lms.lemuel.operation.notification.application;

import java.util.List;

/**
 * 알림 1건을 활성 채널 전체로 팬아웃한 합산 결과.
 *
 * <p>{@code results} 는 생성 시점에 방어 복사되므로, 호출자가 들고 있던 가변 리스트가
 * 이미 공개된 결과를 나중에 바꿀 수 없다.
 */
public final class DispatchResult {

    private final boolean deduped;
    private final List<ChannelResult> results;

    /**
     * @param deduped 중복으로 판정돼 발송 자체를 건너뛴 경우 true
     */
    public DispatchResult(boolean deduped, List<ChannelResult> results) {
        this.deduped = deduped;
        this.results = List.copyOf(results);
    }

    /** 중복 스킵 — 앞선 배달이 이미 처리했다. 실패가 아니다. */
    public static DispatchResult skipped() {
        return new DispatchResult(true, List.of());
    }

    public boolean deduped() {
        return deduped;
    }

    public List<ChannelResult> results() {
        return results;
    }

    /** 하나라도 도달했는가 — 부분 성공을 실패로 취급하지 않기 위한 판정. */
    public boolean anySucceeded() {
        return results.stream().anyMatch(ChannelResult.Success.class::isInstance);
    }

    /** 전건 성공. 결과가 비어 있으면(활성 채널 0개) 성공이 아니다. */
    public boolean allSucceeded() {
        return !results.isEmpty() && results.stream().allMatch(ChannelResult.Success.class::isInstance);
    }
}
