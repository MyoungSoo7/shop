package github.lms.lemuel.operation.site.application.port.out;

import github.lms.lemuel.operation.site.domain.Popup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 팝업 조회 포트 — 저장 의도({@link SavePopupPort})와 분리한다(ISP).
 *
 * <p>페이지네이션이 없다. 팝업은 사이트 전체에 몇 개 있는 목록이고, 이 화면의 주된 조작이
 * <b>순서 바꾸기</b>라서다 — 페이지로 자르면 1페이지 항목을 3페이지로 내리는 조작이 표현되지 않는다.
 * (dentis 는 페이지를 나눴고, 그래서 순서 컬럼도 없이 reg_date 역순으로만 보여 줬다.)
 */
public interface LoadPopupPort {

    Optional<Popup> findById(UUID id);

    /** 관리 목록 — 지운 것은 빼고 노출 순서대로. 켜짐/꺼짐은 둘 다 나온다. */
    List<Popup> findAll();

    /** 그 시각에 실제로 떠야 하는 팝업들. 판단 자체는 도메인({@code Popup#isVisibleAt})이 한다. */
    List<Popup> findVisibleAt(Instant now);
}
