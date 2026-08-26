package github.lms.lemuel.operation.site.domain;

import github.lms.lemuel.operation.site.domain.exception.InvalidPopupStateException;

import java.time.Instant;
import java.util.UUID;

/**
 * 사이트 팝업 애그리거트 루트 — <b>언제 떠 있는가</b>가 이 애그리거트의 전부다.
 *
 * <p>노출 여부는 세 가지가 모두 참일 때다: 지우지 않았고, 켜 두었고(active), 지금이 노출 구간 안이다.
 * dentis 의 공개 조회는 {@code use_yn='Y' AND end_date > now()} 만 봤다 — 시작 시각을 보지 않아
 * <b>다음 달로 예약한 팝업이 저장 즉시 떴다</b>. 그 판단을 쿼리가 아니라 여기 두는 이유는, 관리
 * 화면의 "지금 노출 중" 표시와 공개 조회가 서로 다른 규칙을 갖지 않게 하기 위해서다.
 *
 * <p>축이 둘인 것({@code active} / {@code deleted})은 강사 명부와 같다 — 잠시 내리는 것과 치우는
 * 것은 다른 결정이고, 합치면 되돌리는 조작이 구분되지 않는다.
 */
public final class Popup {
    private final UUID id;
    private String title;
    private String imageUrl;
    private String linkUrl;
    private boolean openInNewWindow;
    private Instant startsAt;
    private Instant endsAt;
    private int sortOrder;
    private boolean active;
    private boolean deleted;
    private Instant deletedAt;
    private String updatedBy;
    private final long version;

    private Popup(UUID id, String title, String imageUrl, String linkUrl, boolean openInNewWindow,
                  Instant startsAt, Instant endsAt, int sortOrder, boolean active, boolean deleted,
                  Instant deletedAt, String updatedBy, long version) {
        requireWindow(title, startsAt, endsAt);
        this.id = id;
        this.title = title.trim();
        this.imageUrl = imageUrl;
        this.linkUrl = linkUrl;
        this.openInNewWindow = openInNewWindow;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.sortOrder = sortOrder;
        this.active = active;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static Popup register(UUID id, String title, String imageUrl, String linkUrl,
                                 boolean openInNewWindow, Instant startsAt, Instant endsAt,
                                 int sortOrder, String actor) {
        return new Popup(id, title, imageUrl, linkUrl, openInNewWindow, startsAt, endsAt, sortOrder,
                true, false, null, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점. */
    public static Popup rehydrate(UUID id, String title, String imageUrl, String linkUrl,
                                  boolean openInNewWindow, Instant startsAt, Instant endsAt,
                                  int sortOrder, boolean active, boolean deleted, Instant deletedAt,
                                  String updatedBy, long version) {
        return new Popup(id, title, imageUrl, linkUrl, openInNewWindow, startsAt, endsAt, sortOrder,
                active, deleted, deletedAt, updatedBy, version);
    }

    public void update(String title, String imageUrl, String linkUrl, boolean openInNewWindow,
                       Instant startsAt, Instant endsAt, int sortOrder, String actor) {
        requireAlive();
        requireWindow(title, startsAt, endsAt);
        this.title = title.trim();
        this.imageUrl = imageUrl;
        this.linkUrl = linkUrl;
        this.openInNewWindow = openInNewWindow;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.sortOrder = sortOrder;
        this.updatedBy = actor;
    }

    public void activate(String actor) {
        requireAlive();
        this.active = true;
        this.updatedBy = actor;
    }

    public void deactivate(String actor) {
        requireAlive();
        this.active = false;
        this.updatedBy = actor;
    }

    public void delete(String actor) {
        requireAlive();
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.active = false;
        this.updatedBy = actor;
    }

    /**
     * 그 시각에 실제로 떠 있어야 하는가. 시작은 포함, 종료는 배타다 — 종료 시각이 지나면 즉시 내린다.
     * dentis 가 빠뜨린 시작 시각 검사가 여기 들어 있다.
     */
    public boolean isVisibleAt(Instant now) {
        return !deleted && active && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /** 아직 시작 전인가 — 관리 화면이 "예약됨"과 "종료됨"을 구분해 보여 줄 수 있어야 한다. */
    public boolean isScheduledAt(Instant now) {
        return !deleted && now.isBefore(startsAt);
    }

    public boolean isExpiredAt(Instant now) {
        return !deleted && !now.isBefore(endsAt);
    }

    private void requireAlive() {
        if (deleted) throw new InvalidPopupStateException("deleted popup cannot be modified: " + id);
    }

    /**
     * 종료가 시작보다 빠른 저장을 막는다. dentis 는 막지 않았고, 그렇게 저장된 팝업은 영영 안 뜨는데
     * 오류도 안 났다 — 운영자는 "왜 안 뜨지"만 남는다.
     */
    private static void requireWindow(String title, Instant startsAt, Instant endsAt) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (startsAt == null || endsAt == null) throw new IllegalArgumentException("exposure window is required");
        if (!endsAt.isAfter(startsAt)) {
            throw new InvalidPopupStateException("exposure window must end after it starts");
        }
    }

    public UUID id() { return id; }
    public String title() { return title; }
    public String imageUrl() { return imageUrl; }
    public String linkUrl() { return linkUrl; }
    public boolean openInNewWindow() { return openInNewWindow; }
    public Instant startsAt() { return startsAt; }
    public Instant endsAt() { return endsAt; }
    public int sortOrder() { return sortOrder; }
    public boolean active() { return active; }
    public boolean deleted() { return deleted; }
    public Instant deletedAt() { return deletedAt; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
