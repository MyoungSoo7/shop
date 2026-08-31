package github.lms.lemuel.seller.application.port.dto;

import java.util.List;

/** 셀러 주문 목록 한 페이지. {@code totalElements} 는 필터를 적용한 전체 건수다. */
public record SellerOrderPage(
        List<SellerOrderView> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
