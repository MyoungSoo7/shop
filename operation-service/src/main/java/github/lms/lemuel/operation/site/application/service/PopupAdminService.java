package github.lms.lemuel.operation.site.application.service;

import github.lms.lemuel.operation.site.application.port.out.LoadPopupPort;
import github.lms.lemuel.operation.site.application.port.out.SavePopupPort;
import github.lms.lemuel.operation.site.domain.Popup;
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
 */
@Service
public class PopupAdminService {

    private final LoadPopupPort loadPopup;
    private final SavePopupPort savePopup;
    private final Clock clock;

    public PopupAdminService(LoadPopupPort loadPopup, SavePopupPort savePopup, Clock clock) {
        this.loadPopup = loadPopup;
        this.savePopup = savePopup;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Popup> list() { return loadPopup.findAll(); }

    @Transactional(readOnly = true)
    public Popup get(UUID id) { return popupOrThrow(id); }

    /** 지금 실제로 떠야 하는 팝업들 — 관리 화면의 미리보기가 공개 화면과 같은 규칙을 쓰게 한다. */
    @Transactional(readOnly = true)
    public List<Popup> visibleNow() { return loadPopup.findVisibleAt(now()); }

    @Transactional
    public Popup register(String title, String imageUrl, String linkUrl, boolean openInNewWindow,
                          Instant startsAt, Instant endsAt, int sortOrder, String actor) {
        return savePopup.save(Popup.register(UUID.randomUUID(), title, imageUrl, linkUrl,
                openInNewWindow, startsAt, endsAt, sortOrder, actor));
    }

    @Transactional
    public Popup update(UUID id, String title, String imageUrl, String linkUrl, boolean openInNewWindow,
                        Instant startsAt, Instant endsAt, int sortOrder, String actor) {
        Popup popup = popupOrThrow(id);
        popup.update(title, imageUrl, linkUrl, openInNewWindow, startsAt, endsAt, sortOrder, actor);
        return savePopup.save(popup);
    }

    /**
     * 켜고 끈다. 노출 구간과는 별개의 축이다 — 구간이 남아 있어도 꺼 두면 안 뜨고, 켜 두어도
     * 구간 밖이면 안 뜬다. 둘을 하나로 합치면 "잠시 내렸다"가 "일정을 지웠다"와 같아진다.
     */
    @Transactional
    public Popup changeActivation(UUID id, boolean active, String actor) {
        Popup popup = popupOrThrow(id);
        if (active) popup.activate(actor); else popup.deactivate(actor);
        return savePopup.save(popup);
    }

    @Transactional
    public Popup delete(UUID id, String actor) {
        Popup popup = popupOrThrow(id);
        popup.delete(actor);
        return savePopup.save(popup);
    }

    public Instant now() { return clock.instant(); }

    /** 조회를 애노테이션 없는 내부 메서드로 분리한다 — 쓰기 메서드가 get() 을 자기호출하면 프록시를 우회한다(aop-proxy-gate). */
    private Popup popupOrThrow(UUID id) {
        return loadPopup.findById(id).orElseThrow(() -> new PopupNotFoundException(id));
    }

    public static class PopupNotFoundException extends RuntimeException {
        public PopupNotFoundException(UUID id) { super("popup not found: " + id); }
    }
}
