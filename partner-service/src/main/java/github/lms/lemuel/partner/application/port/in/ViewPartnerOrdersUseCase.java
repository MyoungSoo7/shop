package github.lms.lemuel.partner.application.port.in;

import github.lms.lemuel.partner.application.port.dto.OrderQuery;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderPage;
import github.lms.lemuel.partner.application.port.dto.PartnerOrderView;
import github.lms.lemuel.partner.domain.PartnerScope;

import java.util.Optional;

/** 주문(결제) 목록·상세. */
public interface ViewPartnerOrdersUseCase {

    PartnerOrderPage orders(PartnerScope scope, OrderQuery query);

    /**
     * 단건 조회.
     *
     * <p>"불러온 뒤 소유자를 검사" 가 아니라 <b>처음부터 내 셀러로 필터</b>한다. 남의 주문번호를
     * 넣으면 존재 자체가 드러나지 않고 그냥 비어서 돌아온다 — 검사를 빠뜨릴 자리가 없다.
     */
    Optional<PartnerOrderView> order(PartnerScope scope, long orderId);
}
