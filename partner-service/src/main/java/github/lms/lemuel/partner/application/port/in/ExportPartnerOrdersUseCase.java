package github.lms.lemuel.partner.application.port.in;

import github.lms.lemuel.partner.application.port.dto.OrderExport;
import github.lms.lemuel.partner.application.port.dto.OrderQuery;
import github.lms.lemuel.partner.domain.PartnerScope;

/**
 * 주문 내역 CSV.
 *
 * <p>레퍼런스에는 "다운로드 사유" 입력이 있었지만 옮기지 않았다. 이 CSV 에는 개인정보가 한 줄도
 * 없다(구매자는 숫자 user_id 로도 나오지 않는다). 보호할 대상이 없는 통제는 보호받고 있다는
 * 착각만 만든다 — 나중에 어떤 이벤트가 PII 를 싣기 시작하면 그때 실제로 설계해야 한다.
 */
public interface ExportPartnerOrdersUseCase {

    OrderExport export(PartnerScope scope, OrderQuery query);
}
