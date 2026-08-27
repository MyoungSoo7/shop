package github.lms.lemuel.operation.site.application.port.in;

import github.lms.lemuel.operation.site.domain.exception.PopupNotFoundException;

import java.time.Instant;
import java.util.UUID;

/**
 * 팝업 등록·수정·on/off·삭제 창구.
 *
 * <p>{@code actor} 를 커맨드에 넣지 않고 따로 받는다. 커맨드는 <b>요청 본문에서 온 것</b>이고
 * actor 는 <b>인증에서 온 것</b>이라 출처가 다르다. 한 레코드에 섞으면 클라이언트가 보낸 이름으로
 * 감사 로그가 남는 실수가 타입 위에 드러나지 않는다.
 */
public interface ManagePopupUseCase {

    PopupView register(SaveCommand command, String actor);

    /** @throws PopupNotFoundException 해당 id 의 팝업이 없을 때 */
    PopupView update(UUID id, SaveCommand command, String actor);

    /**
     * 켜고 끈다. 노출 구간과는 별개의 축이다 — 구간이 남아 있어도 꺼 두면 안 뜨고, 켜 두어도
     * 구간 밖이면 안 뜬다. 둘을 하나로 합치면 "잠시 내렸다"가 "일정을 지웠다"와 같아진다.
     *
     * @throws PopupNotFoundException 해당 id 의 팝업이 없을 때
     */
    PopupView changeActivation(UUID id, boolean active, String actor);

    /** @throws PopupNotFoundException 해당 id 의 팝업이 없을 때 */
    PopupView delete(UUID id, String actor);

    /**
     * 등록·수정 스펙.
     *
     * <p>기본값 보정({@code openInNewWindow} 미지정 → 켬 등)은 <b>어댑터에서 끝낸다</b>.
     * "안 보냈다"와 "끄겠다"의 구분은 HTTP 요청의 문제이지 유스케이스의 문제가 아니고,
     * 그 구분을 여기까지 끌고 오면 모든 어댑터가 같은 보정 규칙을 알아야 한다.
     */
    record SaveCommand(String title, String imageUrl, String linkUrl, boolean openInNewWindow,
                       Instant startsAt, Instant endsAt, int sortOrder) {
    }
}
