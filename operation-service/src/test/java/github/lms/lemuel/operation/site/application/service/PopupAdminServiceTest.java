package github.lms.lemuel.operation.site.application.service;

import github.lms.lemuel.operation.site.application.port.out.LoadPopupPort;
import github.lms.lemuel.operation.site.application.port.out.SavePopupPort;
import github.lms.lemuel.operation.site.domain.Popup;
import github.lms.lemuel.operation.site.domain.exception.InvalidPopupStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팝업 콘솔 — 도메인 위층에서 볼 것은 둘이다: <b>없는 팝업에 대한 조작이 404 로 갈라지는가</b>,
 * 그리고 <b>지금 뜨는 목록이 고정된 시계로 결정되는가</b>.
 *
 * <p>{@link Clock} 을 고정해 둔다. 실제 시계를 쓰면 "지금 노출 중" 테스트가 특정 날짜에만 통과하는
 * 시한폭탄이 된다 — 통과하는 동안에는 아무도 눈치채지 못한다.
 */
class PopupAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    private final LoadPopupPort loadPopup = mock(LoadPopupPort.class);
    private final SavePopupPort savePopup = mock(SavePopupPort.class);
    private final PopupAdminService service =
            new PopupAdminService(loadPopup, savePopup, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID id = UUID.randomUUID();
    private final Instant start = NOW.minus(Duration.ofDays(1));
    private final Instant end = NOW.plus(Duration.ofDays(1));

    private Popup alive() {
        return Popup.rehydrate(id, "추석 휴진", null, null, true, start, end, 0,
                true, false, null, "admin", 0L);
    }

    private void savePassesThrough() {
        when(savePopup.save(any(Popup.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("등록하면 식별자를 서버가 발급한다 — dentis 의 max+1 경합이 사라지는 지점")
    void registerAllocatesId() {
        savePassesThrough();

        Popup created = service.register("공지", null, null, true, start, end, 0, "admin");

        assertThat(created.id()).isNotNull();
        assertThat(created.active()).isTrue();
        verify(savePopup).save(any(Popup.class));
    }

    @Test
    @DisplayName("없는 팝업을 열면 404 로 갈라진다")
    void getMissing() {
        when(loadPopup.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(PopupAdminService.PopupNotFoundException.class);
    }

    @Test
    @DisplayName("없는 팝업에 대한 쓰기 조작도 저장을 부르지 않는다")
    void writesOnMissingNeverSave() {
        when(loadPopup.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, "x", null, null, true, start, end, 0, "admin"))
                .isInstanceOf(PopupAdminService.PopupNotFoundException.class);
        assertThatThrownBy(() -> service.changeActivation(id, false, "admin"))
                .isInstanceOf(PopupAdminService.PopupNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id, "admin"))
                .isInstanceOf(PopupAdminService.PopupNotFoundException.class);

        verify(savePopup, never()).save(any(Popup.class));
    }

    @Test
    @DisplayName("수정은 도메인 규칙을 그대로 받는다 — 뒤집힌 구간은 저장까지 가지 않는다")
    void updateRejectsInvertedWindow() {
        when(loadPopup.findById(id)).thenReturn(Optional.of(alive()));

        assertThatThrownBy(() -> service.update(id, "x", null, null, true, end, start, 0, "admin"))
                .isInstanceOf(InvalidPopupStateException.class);

        verify(savePopup, never()).save(any(Popup.class));
    }

    @Test
    @DisplayName("끄고 켜는 것은 같은 한 메서드가 판단한다")
    void changeActivationBothWays() {
        when(loadPopup.findById(id)).thenReturn(Optional.of(alive()));
        savePassesThrough();

        assertThat(service.changeActivation(id, false, "admin").active()).isFalse();

        when(loadPopup.findById(id)).thenReturn(Optional.of(
                Popup.rehydrate(id, "추석 휴진", null, null, true, start, end, 0,
                        false, false, null, "admin", 1L)));

        assertThat(service.changeActivation(id, true, "admin").active()).isTrue();
    }

    @Test
    @DisplayName("삭제는 팝업을 돌려준다 — 화면이 삭제 표시를 즉시 그릴 수 있어야 한다")
    void deleteReturnsPopup() {
        when(loadPopup.findById(id)).thenReturn(Optional.of(alive()));
        savePassesThrough();

        Popup deleted = service.delete(id, "admin");

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.active()).isFalse();
    }

    @Test
    @DisplayName("지금 뜨는 목록은 고정된 시계로 조회한다")
    void visibleNowUsesTheInjectedClock() {
        when(loadPopup.findVisibleAt(NOW)).thenReturn(List.of(alive()));

        assertThat(service.visibleNow()).hasSize(1);
        assertThat(service.now()).isEqualTo(NOW);
        verify(loadPopup).findVisibleAt(NOW);
    }

    @Test
    @DisplayName("관리 목록은 켜짐/꺼짐을 가리지 않고 그대로 돌려준다")
    void listPassesThrough() {
        Popup off = Popup.rehydrate(UUID.randomUUID(), "꺼둔 팝업", null, null, true, start, end, 1,
                false, false, null, "admin", 0L);
        when(loadPopup.findAll()).thenReturn(List.of(alive(), off));

        assertThat(service.list()).hasSize(2);
    }
}
