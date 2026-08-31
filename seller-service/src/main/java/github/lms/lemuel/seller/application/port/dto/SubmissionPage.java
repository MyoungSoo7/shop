package github.lms.lemuel.seller.application.port.dto;

import java.util.List;

/** 신청서 목록 한 페이지. {@code totalElements} 는 필터를 적용한 전체 건수다. */
public record SubmissionPage(
        List<SubmissionView> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
