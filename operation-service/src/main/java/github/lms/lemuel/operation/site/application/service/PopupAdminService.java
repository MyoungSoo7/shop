package github.lms.lemuel.operation.site.application.service;

import github.lms.lemuel.operation.site.application.port.in.ManagePopupUseCase;
import github.lms.lemuel.operation.site.application.port.in.PopupView;
import github.lms.lemuel.operation.site.application.port.in.QueryPopupUseCase;
import github.lms.lemuel.operation.site.application.port.out.LoadPopupPort;
import github.lms.lemuel.operation.site.application.port.out.SavePopupPort;
import github.lms.lemuel.operation.site.domain.Popup;
import github.lms.lemuel.operation.site.domain.exception.PopupNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 사이트 팝업 콘솔 — dentis 의 admin/site/popup 묶음(popup_list · popup_reg)이 하던 일.
 *
 * <p>{@link Clock} 을 주입받는다. "지금 노출 중인가"는 이 슬라이스의 유일한 판단이고, 그것을
 * {@code Instant.now()} 로 직접 부르면 테스트가 실제 시계에 매달려 경계(시작 직전·종료 직후)를
 * 확인할 방법이 없어진다.
 *
 * <p>시계를 <b>밖으로 내보내지 않는다</b>. 예전에는 어댑터가 {@code now()} 를 물어 스스로 판정했는데,
 * 그러면 시계가 사실상 공개 API가 되고 판정 규칙이 어댑터마다 복제된다. 지금은 판정까지 마친
 * {@link PopupView} 만 나간다.
 */
@Service
public class PopupAdminService implements QueryPopupUseCase, ManagePopupUseCase {

    private final LoadPopupPort loadPopup;
    private final SavePopupPort savePopup;
    private final Clock clock;

    public PopupAdminService(LoadPopupPort loadPopup, SavePopupPort savePopup, Clock clock) {
        this.loadPopup = loadPopup;
        this.savePopup = savePopup;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PopupView> list() {
        return viewsOf(loadPopup.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public PopupView get(UUID id) {
        return viewOf(popupOrThrow(id), clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PopupView> visibleNow() {
        return viewsOf(loadPopup.findVisibleAt(clock.instant()));
    }

    @Override
    @Transactional
    public PopupView register(SaveCommand command, String actor) {
        Popup popup = Popup.register(UUID.randomUUID(), command.title(), command.imageUrl(),
                command.linkUrl(), command.openInNewWindow(), command.startsAt(), command.endsAt(),
                command.sortOrder(), actor);
        return viewOf(savePopup.save(popup), clock.instant());
    }

    @Override
    @Transactional
    public PopupView update(UUID id, SaveCommand command, String actor) {
        Popup popup = popupOrThrow(id);
        popup.update(command.title(), command.imageUrl(), command.linkUrl(),
                command.openInNewWindow(), command.startsAt(), command.endsAt(),
                command.sortOrder(), actor);
        return viewOf(savePopup.save(popup), clock.instant());
    }

    @Override
    @Transactional
    public PopupView changeActivation(UUID id, boolean active, String actor) {
        Popup popup = popupOrThrow(id);
        if (active) popup.activate(actor); else popup.deactivate(actor);
        return viewOf(savePopup.save(popup), clock.instant());
    }

    @Override
    @Transactional
    public PopupView delete(UUID id, String actor) {
        Popup popup = popupOrThrow(id);
        popup.delete(actor);
        return viewOf(savePopup.save(popup), clock.instant());
    }

    /** 목록은 한 시각으로 <b>한 번에</b> 판정한다 — 항목마다 시계를 다시 읽으면 경계에서 어긋난다. */
    private List<PopupView> viewsOf(List<Popup> popups) {
        Instant now = clock.instant();
        return popups.stream().map(popup -> viewOf(popup, now)).toList();
    }

    private static PopupView viewOf(Popup popup, Instant now) {
        return new PopupView(popup, popup.isVisibleAt(now), popup.isScheduledAt(now),
                popup.isExpiredAt(now));
    }

    /** 조회를 애노테이션 없는 내부 메서드로 분리한다 — 쓰기 메서드가 get() 을 자기호출하면 프록시를 우회한다(aop-proxy-gate). */
    private Popup popupOrThrow(UUID id) {
        return loadPopup.findById(id).orElseThrow(() -> new PopupNotFoundException(id));
    }
}
