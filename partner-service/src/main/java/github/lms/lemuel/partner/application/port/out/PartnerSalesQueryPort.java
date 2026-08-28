package github.lms.lemuel.partner.application.port.out;

import github.lms.lemuel.partner.application.port.dto.BestProductView;
import github.lms.lemuel.partner.application.port.dto.DailySalesView;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderView;
import github.lms.lemuel.partner.application.port.dto.SalesSummaryView;

import java.time.LocalDate;
import java.util.List;

/**
 * 매출 읽기 모델 조회.
 *
 * <p><b>모든 메서드가 {@code sellerId} 를 첫 인자로 받고, 구현은 그것을 WHERE 절에 강제로
 * 넣는다.</b> "전체 조회 후 필터" 형태의 메서드를 두지 않는 이유는, 그런 메서드는 필터를
 * 빠뜨려도 컴파일되고 테스트도 통과하기 때문이다 — 운영에서 남의 매출이 보일 때까지.
 *
 * <p>여기서 {@code sellerId} 가 {@code long} 인 것은, 이 계층에 오기 전에
 * {@code PartnerScope.requireSellerId()} 를 이미 통과했다는 뜻이다.
 */
public interface PartnerSalesQueryPort {

    SalesSummaryView summary(long sellerId, LocalDate from, LocalDate to);

    List<DailySalesView> daily(long sellerId, LocalDate from, LocalDate to);

    List<BestProductView> bestProducts(long sellerId, LocalDate from, LocalDate to, int limit);

    /** 기간 안에 결제시각을 수신 시각으로 대체한 행이 있는가(화면 각주용). */
    boolean hasEstimatedCaptureDates(long sellerId, LocalDate from, LocalDate to);

    long countOrders(long sellerId, LocalDate from, LocalDate to, Long orderId);

    List<PartnerOrderView> findOrders(long sellerId, LocalDate from, LocalDate to, Long orderId,
                                      int limit, long offset);
}
