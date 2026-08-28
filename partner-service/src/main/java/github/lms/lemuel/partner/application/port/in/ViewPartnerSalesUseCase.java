package github.lms.lemuel.partner.application.port.in;

import github.lms.lemuel.partner.application.port.dto.PartnerDashboardView;
import github.lms.lemuel.partner.domain.PartnerScope;

import java.time.LocalDate;

/** 대시보드 — 기간 요약 + 일자별 + 베스트 상품. */
public interface ViewPartnerSalesUseCase {

    /**
     * @throws github.lms.lemuel.partner.domain.exception.NoSalesScopeException
     *         셀러가 아닌 조직(CORPORATE) 또는 셀러 식별자를 유도하지 못한 조직
     */
    PartnerDashboardView dashboard(PartnerScope scope, LocalDate from, LocalDate to);
}
