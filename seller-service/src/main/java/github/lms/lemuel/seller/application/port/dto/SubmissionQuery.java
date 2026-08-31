package github.lms.lemuel.seller.application.port.dto;

import github.lms.lemuel.seller.domain.SubmissionStatus;

/**
 * 신청서 목록 조회 조건.
 *
 * <p>{@link SellerOrderQuery} 와 같은 이유로 셀러 식별자를 담지 않는다 — 조회 대상은 언제나 스코프가
 * 정한다. 여기에 {@code sellerId} 를 두면 그 값을 요청 파라미터에서 채우는 코드가 언젠가 생기고,
 * 그날부터 남의 신청서가 조회된다.
 *
 * @param status null 이면 전체 상태. 운영자 대기목록은 이 값을 서비스가 SUBMITTED 로 고정한다.
 */
public record SubmissionQuery(SubmissionStatus status, int page, int size) {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;

    /** 페이지·크기를 안전 범위로 접는다. 거절하지 않는 이유는 {@link SellerOrderQuery} 와 같다. */
    public SubmissionQuery normalized() {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new SubmissionQuery(status, safePage, safeSize);
    }
}
