package github.lms.lemuel.operation.site.adapter.in.web;

import github.lms.lemuel.operation.site.application.port.in.ManagePopupUseCase.SaveCommand;
import github.lms.lemuel.operation.site.application.port.in.ManagePopupUseCase;
import github.lms.lemuel.operation.site.application.port.in.PopupView;
import github.lms.lemuel.operation.site.application.port.in.QueryPopupUseCase;
import github.lms.lemuel.operation.site.domain.Popup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 사이트 팝업 콘솔 — {@code /api/ops/popups}.
 *
 * <p>경로를 {@code /api/ops/**} 아래에 둔 것은 배선을 하나도 늘리지 않기 위해서다. 게이트웨이는
 * 이미 이 접두사를 operation-service 로 보내고, {@code OperationSecurityConfig}(@Order(1)) 가
 * 이미 ADMIN 을 요구한다. 새 접두사를 만들면 라우팅 규칙과 보안 규칙이 각각 한 벌씩 더 생기고,
 * 둘 중 하나만 고쳐지는 날 조용히 열린다.
 */
@RestController
@RequestMapping("/api/ops/popups")
public class AdminPopupController {

    private final QueryPopupUseCase queryPopup;
    private final ManagePopupUseCase managePopup;

    public AdminPopupController(QueryPopupUseCase queryPopup, ManagePopupUseCase managePopup) {
        this.queryPopup = queryPopup;
        this.managePopup = managePopup;
    }

    /** 관리 목록 — 지운 것만 빼고 노출 순서대로 전부. 페이지네이션이 없는 이유는 포트 문서 참조. */
    @GetMapping
    public List<PopupResponse> list() {
        return queryPopup.list().stream().map(PopupResponse::from).toList();
    }

    /**
     * 지금 실제로 뜨는 것들 — 운영자가 "내가 켠 게 진짜 보이나"를 저장 직후 확인하는 자리다.
     * 공개 화면과 같은 판정({@code Popup#isVisibleAt})을 쓰기 때문에 의미가 있다.
     */
    @GetMapping("/visible")
    public List<PopupResponse> visible() {
        return queryPopup.visibleNow().stream().map(PopupResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PopupResponse get(@PathVariable UUID id) {
        return PopupResponse.from(queryPopup.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PopupResponse register(@Valid @RequestBody SaveRequest request, Authentication auth) {
        return PopupResponse.from(managePopup.register(request.toCommand(), auth.getName()));
    }

    @PutMapping("/{id}")
    public PopupResponse update(@PathVariable UUID id, @Valid @RequestBody SaveRequest request,
                                Authentication auth) {
        return PopupResponse.from(managePopup.update(id, request.toCommand(), auth.getName()));
    }

    @PutMapping("/{id}/activation")
    public PopupResponse changeActivation(@PathVariable UUID id,
                                          @Valid @RequestBody ActivationRequest request,
                                          Authentication auth) {
        return PopupResponse.from(managePopup.changeActivation(id, request.active(), auth.getName()));
    }

    /** 치운다. 204 가 아니라 바뀐 팝업을 돌려준다 — 화면이 삭제 표시를 즉시 그릴 수 있어야 한다. */
    @DeleteMapping("/{id}")
    public PopupResponse delete(@PathVariable UUID id, Authentication auth) {
        return PopupResponse.from(managePopup.delete(id, auth.getName()));
    }

    /**
     * 등록·수정 요청.
     *
     * <p>{@code openInNewWindow} 와 {@code sortOrder} 를 {@code Boolean}/{@code Integer} 로 받는 이유는
     * 원시 타입이면 필드를 빼먹은 요청이 조용히 {@code false}/{@code 0} 으로 저장되기 때문이다 —
     * 안 보낸 것과 "새 창을 끄겠다"가 구분되지 않는다. 여기서는 안 보낸 쪽을 기본값으로 명시한다.
     *
     * <p>그 보정은 여기서 끝난다. 유스케이스는 값이 <b>정해진</b> 커맨드만 받는다.
     */
    public record SaveRequest(@NotBlank String title, String imageUrl, String linkUrl,
                              Boolean openInNewWindow,
                              @NotNull Instant startsAt, @NotNull Instant endsAt,
                              Integer sortOrder) {
        boolean openInNewWindowOrDefault() { return openInNewWindow == null || openInNewWindow; }
        int sortOrderOrDefault() { return sortOrder == null ? 0 : sortOrder; }

        SaveCommand toCommand() {
            return new SaveCommand(title, imageUrl, linkUrl, openInNewWindowOrDefault(),
                    startsAt, endsAt, sortOrderOrDefault());
        }
    }

    public record ActivationRequest(@NotNull Boolean active) { }

    /**
     * 응답에 {@code visible}·{@code scheduled}·{@code expired} 를 실어 보낸다. 화면이 시작·종료
     * 시각으로 직접 계산하면 브라우저 시계로 판정하게 되고, 서버가 안 띄우는 팝업을 관리 화면만
     * "노출 중" 이라고 표시하는 어긋남이 생긴다.
     */
    public record PopupResponse(UUID id, String title, String imageUrl, String linkUrl,
                                boolean openInNewWindow, Instant startsAt, Instant endsAt,
                                int sortOrder, boolean active, boolean deleted, Instant deletedAt,
                                boolean visible, boolean scheduled, boolean expired,
                                String updatedBy, long version) {
        static PopupResponse from(PopupView view) {
            Popup p = view.popup();
            return new PopupResponse(p.id(), p.title(), p.imageUrl(), p.linkUrl(), p.openInNewWindow(),
                    p.startsAt(), p.endsAt(), p.sortOrder(), p.active(), p.deleted(), p.deletedAt(),
                    view.visible(), view.scheduled(), view.expired(),
                    p.updatedBy(), p.version());
        }
    }
}
