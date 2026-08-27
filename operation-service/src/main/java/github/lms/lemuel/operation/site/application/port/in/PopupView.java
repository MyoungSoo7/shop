package github.lms.lemuel.operation.site.application.port.in;

import github.lms.lemuel.operation.site.domain.Popup;

/**
 * 팝업 하나와 <b>서버가 판정한</b> 노출 상태.
 *
 * <p>노출 여부는 시작·종료 시각과 활성 플래그로 계산되는데, 그 계산을 화면이 하면 판정 기준이
 * <b>브라우저 시계</b>가 된다. 서버는 안 띄우는 팝업을 관리 화면만 "노출 중" 이라고 표시하는
 * 어긋남이 그렇게 생긴다. 그래서 판정을 응용 계층에서 끝내고 결과만 실어 보낸다.
 *
 * <p>세 플래그는 한 시각을 기준으로 <b>함께</b> 계산된다. 어댑터가 {@code now()} 를 따로 물어
 * 각 팝업을 스스로 판정하면, 목록을 그리는 사이에 경계를 넘는 팝업이 서로 다른 시각으로 판정된다.
 *
 * @param popup     팝업 자체
 * @param visible   지금 실제로 떠야 하는가
 * @param scheduled 아직 시작 전인가
 * @param expired   이미 끝났는가
 */
public record PopupView(Popup popup, boolean visible, boolean scheduled, boolean expired) {
}
