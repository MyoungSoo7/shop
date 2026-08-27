package github.lms.lemuel.operation.site.application.port.in;

import github.lms.lemuel.operation.site.domain.exception.PopupNotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * 팝업 조회 창구.
 *
 * <p>페이지네이션이 없다. 팝업은 동시에 몇 개 뜨는 물건이고 관리자가 순서를 눈으로 맞추는
 * 대상이라, 페이지로 잘리면 정렬 자체를 볼 수 없다.
 */
public interface QueryPopupUseCase {

    /** 관리 목록 — 지운 것만 빼고 노출 순서대로 전부. */
    List<PopupView> list();

    /**
     * 지금 실제로 뜨는 것들. 관리 화면의 미리보기가 공개 화면과 <b>같은 판정</b>을 쓰게 한다 —
     * 미리보기가 따로 계산하면 "관리 화면에서는 보이는데 실제로는 안 뜨는" 상태가 생긴다.
     */
    List<PopupView> visibleNow();

    /** @throws PopupNotFoundException 해당 id 의 팝업이 없을 때 */
    PopupView get(UUID id);
}
