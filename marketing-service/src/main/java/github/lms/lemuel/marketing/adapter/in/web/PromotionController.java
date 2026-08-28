package github.lms.lemuel.marketing.adapter.in.web;

import github.lms.lemuel.marketing.application.port.dto.AttendanceBoardView;
import github.lms.lemuel.marketing.application.port.dto.CheckInResultView;
import github.lms.lemuel.marketing.application.port.dto.DrawResultView;
import github.lms.lemuel.marketing.application.port.dto.LuckyboxBoardView;
import github.lms.lemuel.marketing.application.port.dto.PromotionSummary;
import github.lms.lemuel.marketing.application.port.in.CheckInUseCase;
import github.lms.lemuel.marketing.application.port.in.DrawLuckyboxUseCase;
import github.lms.lemuel.marketing.application.port.in.ViewAttendanceUseCase;
import github.lms.lemuel.marketing.application.port.in.ViewLuckyboxUseCase;
import github.lms.lemuel.marketing.application.port.in.ViewPromotionsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 고객 화면용 프로모션 API.
 *
 * <p>회원 식별자를 받는 파라미터가 하나도 없다는 점이 이 컨트롤러의 핵심이다 — 이유는
 * {@link CurrentMember} 주석에 있다. 날짜도 받지 않는다: 클라이언트가 날짜를 보낼 수 있으면
 * 어제 날짜로 출석을 소급해 찍을 수 있다. "오늘" 은 {@link MarketingClock} 이 KST 로 정한다.
 *
 * <p>{@code campaignId} 는 선택이다. 없으면 오늘 진행 중인 것 중 하나를 서비스가 고른다 —
 * 레거시는 정렬 없는 {@code ROWNUM = 1} 이라 캠페인이 둘 이상이면 새로고침할 때마다 다른 이벤트가
 * 떴다. 지금은 시작일·이름 순으로 결정적으로 고른다.
 */
@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    private final ViewPromotionsUseCase viewPromotionsUseCase;
    private final ViewAttendanceUseCase viewAttendanceUseCase;
    private final CheckInUseCase checkInUseCase;
    private final ViewLuckyboxUseCase viewLuckyboxUseCase;
    private final DrawLuckyboxUseCase drawLuckyboxUseCase;

    public PromotionController(ViewPromotionsUseCase viewPromotionsUseCase,
                               ViewAttendanceUseCase viewAttendanceUseCase,
                               CheckInUseCase checkInUseCase,
                               ViewLuckyboxUseCase viewLuckyboxUseCase,
                               DrawLuckyboxUseCase drawLuckyboxUseCase) {
        this.viewPromotionsUseCase = viewPromotionsUseCase;
        this.viewAttendanceUseCase = viewAttendanceUseCase;
        this.checkInUseCase = checkInUseCase;
        this.viewLuckyboxUseCase = viewLuckyboxUseCase;
        this.drawLuckyboxUseCase = drawLuckyboxUseCase;
    }

    /** 오늘 진행 중인 이벤트 목록 — 비로그인도 본다. */
    @GetMapping
    public ResponseEntity<List<PromotionSummary>> running() {
        return ResponseEntity.ok(viewPromotionsUseCase.runningOn(MarketingClock.today()));
    }

    @GetMapping("/attendance")
    public ResponseEntity<AttendanceBoardView> attendanceBoard(
            @RequestParam(required = false) UUID campaignId) {
        LocalDate today = MarketingClock.today();
        return ResponseEntity.ok(viewAttendanceUseCase.board(campaignId, CurrentMember.require(), today));
    }

    @PostMapping("/attendance/check-in")
    public ResponseEntity<CheckInResultView> checkIn(@RequestParam(required = false) UUID campaignId) {
        LocalDate today = MarketingClock.today();
        return ResponseEntity.ok(checkInUseCase.checkIn(campaignId, CurrentMember.require(), today));
    }

    @GetMapping("/luckybox")
    public ResponseEntity<LuckyboxBoardView> luckyboxBoard(@RequestParam(required = false) UUID campaignId) {
        LocalDate today = MarketingClock.today();
        return ResponseEntity.ok(viewLuckyboxUseCase.board(campaignId, CurrentMember.require(), today));
    }

    @PostMapping("/luckybox/draw")
    public ResponseEntity<DrawResultView> draw(@RequestParam(required = false) UUID campaignId) {
        LocalDate today = MarketingClock.today();
        return ResponseEntity.ok(drawLuckyboxUseCase.draw(campaignId, CurrentMember.require(), today));
    }
}
