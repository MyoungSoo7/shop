package github.lms.lemuel.partner.application.port.dto;

import java.util.List;

/** 주문 목록 한 페이지. {@code totalElements} 는 필터를 적용한 전체 건수다. */
public record PartnerOrderPage(
        List<PartnerOrderView> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
