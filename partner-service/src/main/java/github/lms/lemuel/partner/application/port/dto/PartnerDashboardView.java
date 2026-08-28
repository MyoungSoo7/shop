package github.lms.lemuel.partner.application.port.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 대시보드 한 판 — 요약 + 일자별 + 베스트 상품을 한 번에 준다.
 *
 * <p>세 조회를 한 응답으로 묶은 것은 화면이 세 번 왕복하지 않게 하려는 것도 있지만, 더 큰 이유는
 * <b>세 값이 같은 기간·같은 스코프에서 나왔음을 보장</b>하기 위해서다. 따로 부르면 그 사이에
 * 새 이벤트가 반영되어 합계와 일자별 합이 어긋날 수 있고, 사용자는 그걸 버그로 신고한다.
 *
 * @param estimatedCaptureDates 기간 안에 {@code capturedAt} 없이 수신 시각으로 대체된 결제가
 *                              있는가. 금액은 정확하지만 자정 근처에서 하루가 밀렸을 수 있어,
 *                              화면이 각주로 알린다. 숨기면 "어제 매출이 왜 다르냐" 가 된다.
 */
public record PartnerDashboardView(
        LocalDate from,
        LocalDate to,
        SalesSummaryView summary,
        List<DailySalesView> daily,
        List<BestProductView> bestProducts,
        boolean estimatedCaptureDates) {
}
