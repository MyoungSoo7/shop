package github.lms.lemuel.partner.adapter.in.web;

import github.lms.lemuel.partner.application.port.dto.PartnerDashboardView;
import github.lms.lemuel.partner.application.port.in.ResolvePartnerScopeUseCase;
import github.lms.lemuel.partner.application.port.in.ViewPartnerSalesUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 매출 대시보드.
 *
 * <p>요약·일별·베스트상품을 <b>한 응답으로</b> 내려주는 것이 이 컨트롤러의 설계다. 엔드포인트를
 * 셋으로 쪼개면 화면이 세 번 부르는데, 그 사이에 컨슈머가 프로젝션을 갱신하면 합계와 일별의
 * 합이 서로 안 맞는 화면이 나온다. 파트너 입장에서는 "숫자가 틀린 백오피스" 이고, 재현되지
 * 않아 버그로 접수되지도 않는다. 한 번에 읽으면 그 창이 없다.
 *
 * <p>기간을 안 주면 최근 30일이다. 기간이 뒤집혔거나 366일을 넘으면 조용히 잘라 맞추지 않고
 * 400 으로 거절한다 — 잘라 맞추면 파트너가 요청한 기간과 화면의 숫자가 달라진다.
 */
@RestController
@RequestMapping("/api/partner")
@RequiredArgsConstructor
public class PartnerSalesController {

    private final ResolvePartnerScopeUseCase resolveScope;
    private final ViewPartnerSalesUseCase viewSales;

    @GetMapping("/dashboard")
    public PartnerDashboardView dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return viewSales.dashboard(resolveScope.resolve(CurrentPartnerUser.requireUserId()), from, to);
    }
}
