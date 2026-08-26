package github.lms.lemuel.operation.site.domain;

import github.lms.lemuel.operation.site.domain.exception.InvalidPopupStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 팝업 애그리거트 — <b>언제 떠 있는가</b>만 검증한다.
 *
 * <p>구간 경계를 시각으로 직접 찍는 이유는 dentis 의 두 결함이 정확히 경계에 있었기 때문이다:
 * 시작 시각을 아예 보지 않았고(예약이 즉시 노출), 종료가 시작보다 빠른 저장을 막지 않았다
 * (영영 안 뜨는데 오류도 없음). 둘 다 "언젠가 뜨겠지"로 보이지 시스템 오류로는 안 보인다.
 */
class PopupTest {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-09-30T00:00:00Z");

    private static Popup popup() {
        return Popup.register(UUID.randomUUID(), "추석 휴진 안내", "https://cdn/x.png",
                "https://site/notice/1", true, START, END, 0, "admin");
    }

    @Test
    @DisplayName("등록하면 켜진 상태로 들어오고 지워지지 않은 상태다")
    void registersActive() {
        Popup popup = popup();

        assertThat(popup.active()).isTrue();
        assertThat(popup.deleted()).isFalse();
        assertThat(popup.deletedAt()).isNull();
        assertThat(popup.version()).isZero();
    }

    @Test
    @DisplayName("제목의 앞뒤 공백은 저장 전에 떨어진다")
    void trimsTitle() {
        Popup popup = Popup.register(UUID.randomUUID(), "  공지  ", null, null, false, START, END, 0, "admin");

        assertThat(popup.title()).isEqualTo("공지");
    }

    @Test
    @DisplayName("시작 직전에는 안 뜨고, 시작 시각 정각에는 뜬다 — dentis 가 빠뜨린 검사")
    void startBoundaryIsInclusive() {
        Popup popup = popup();

        assertThat(popup.isVisibleAt(START.minusMillis(1))).isFalse();
        assertThat(popup.isVisibleAt(START)).isTrue();
    }

    @Test
    @DisplayName("종료 직전까지 뜨고 종료 시각 정각에는 내려간다")
    void endBoundaryIsExclusive() {
        Popup popup = popup();

        assertThat(popup.isVisibleAt(END.minusMillis(1))).isTrue();
        assertThat(popup.isVisibleAt(END)).isFalse();
    }

    @Test
    @DisplayName("예약됨과 종료됨은 서로 다른 상태다 — 화면이 둘을 구분해 보여 줄 수 있어야 한다")
    void scheduledAndExpiredAreDistinct() {
        Popup popup = popup();
        Instant before = START.minus(Duration.ofDays(1));
        Instant after = END.plus(Duration.ofDays(1));

        assertThat(popup.isScheduledAt(before)).isTrue();
        assertThat(popup.isExpiredAt(before)).isFalse();
        assertThat(popup.isScheduledAt(after)).isFalse();
        assertThat(popup.isExpiredAt(after)).isTrue();
    }

    @Test
    @DisplayName("꺼 두면 구간 안이어도 안 뜬다 — 노출 축과 일정 축은 별개다")
    void deactivatedNeverShows() {
        Popup popup = popup();

        popup.deactivate("admin");

        assertThat(popup.active()).isFalse();
        assertThat(popup.isVisibleAt(START.plus(Duration.ofDays(1)))).isFalse();
        // 일정은 그대로 남아 있다 — 다시 켜면 그 구간이 되살아난다.
        assertThat(popup.startsAt()).isEqualTo(START);
        popup.activate("admin");
        assertThat(popup.isVisibleAt(START.plus(Duration.ofDays(1)))).isTrue();
    }

    @Test
    @DisplayName("지우면 꺼지고 지운 시각이 남는다")
    void deleteTurnsItOff() {
        Popup popup = popup();

        popup.delete("admin");

        assertThat(popup.deleted()).isTrue();
        assertThat(popup.active()).isFalse();
        assertThat(popup.deletedAt()).isNotNull();
        assertThat(popup.isVisibleAt(START.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    @DisplayName("지운 팝업은 고치지도 켜지도 못한다")
    void deletedIsFrozen() {
        Popup popup = popup();
        popup.delete("admin");

        assertThatThrownBy(() -> popup.activate("admin"))
                .isInstanceOf(InvalidPopupStateException.class);
        assertThatThrownBy(() -> popup.update("바뀐 제목", null, null, true, START, END, 0, "admin"))
                .isInstanceOf(InvalidPopupStateException.class);
        assertThatThrownBy(() -> popup.delete("admin"))
                .isInstanceOf(InvalidPopupStateException.class);
    }

    @Test
    @DisplayName("종료가 시작보다 빠르거나 같으면 저장 자체가 막힌다")
    void windowMustBePositive() {
        assertThatThrownBy(() -> Popup.register(UUID.randomUUID(), "제목", null, null, true, END, START, 0, "admin"))
                .isInstanceOf(InvalidPopupStateException.class);
        assertThatThrownBy(() -> Popup.register(UUID.randomUUID(), "제목", null, null, true, START, START, 0, "admin"))
                .isInstanceOf(InvalidPopupStateException.class);

        Popup popup = popup();
        assertThatThrownBy(() -> popup.update("제목", null, null, true, END, START, 0, "admin"))
                .isInstanceOf(InvalidPopupStateException.class);
        // 막힌 수정은 아무것도 바꾸지 않는다 — 절반만 반영되면 화면이 유효하지 않은 상태를 보여 준다.
        assertThat(popup.startsAt()).isEqualTo(START);
        assertThat(popup.endsAt()).isEqualTo(END);
    }

    @Test
    @DisplayName("제목과 구간은 필수다")
    void requiredFields() {
        assertThatThrownBy(() -> Popup.register(UUID.randomUUID(), "  ", null, null, true, START, END, 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Popup.register(UUID.randomUUID(), "제목", null, null, true, null, END, 0, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("수정하면 구간·링크·순서가 모두 바뀌고 수정자가 남는다")
    void updateReplacesEveryField() {
        Popup popup = popup();
        Instant newStart = START.plus(Duration.ofDays(10));
        Instant newEnd = END.plus(Duration.ofDays(10));

        popup.update("새 제목", "https://cdn/y.png", "https://site/notice/2", false, newStart, newEnd, 3, "editor");

        assertThat(popup.title()).isEqualTo("새 제목");
        assertThat(popup.imageUrl()).isEqualTo("https://cdn/y.png");
        assertThat(popup.linkUrl()).isEqualTo("https://site/notice/2");
        assertThat(popup.openInNewWindow()).isFalse();
        assertThat(popup.startsAt()).isEqualTo(newStart);
        assertThat(popup.endsAt()).isEqualTo(newEnd);
        assertThat(popup.sortOrder()).isEqualTo(3);
        assertThat(popup.updatedBy()).isEqualTo("editor");
    }

    @Test
    @DisplayName("되살린 팝업은 저장돼 있던 상태 그대로다")
    void rehydrateKeepsState() {
        UUID id = UUID.randomUUID();
        Instant deletedAt = Instant.parse("2026-08-01T00:00:00Z");

        Popup popup = Popup.rehydrate(id, "지운 팝업", null, null, false, START, END, 7,
                false, true, deletedAt, "admin", 4L);

        assertThat(popup.id()).isEqualTo(id);
        assertThat(popup.sortOrder()).isEqualTo(7);
        assertThat(popup.deleted()).isTrue();
        assertThat(popup.deletedAt()).isEqualTo(deletedAt);
        assertThat(popup.version()).isEqualTo(4L);
        // 지운 것은 어떤 시각에도 안 뜬다.
        assertThat(popup.isVisibleAt(START.plus(Duration.ofDays(1)))).isFalse();
        assertThat(popup.isScheduledAt(START.minusMillis(1))).isFalse();
        assertThat(popup.isExpiredAt(END)).isFalse();
    }
}
