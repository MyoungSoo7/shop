package github.lms.lemuel.seller.application.port.in;

import github.lms.lemuel.seller.application.port.dto.SubmissionView;

/**
 * 운영자 심사 — 승인·반려.
 *
 * <p><b>승인은 카탈로그 등록이 아니다.</b> 여기서 하는 일은 상태를 APPROVED 로 바꾸고
 * {@code lemuel.seller.product_approved} 를 outbox 에 적는 것까지다. 실제 상품 생성은
 * order-service 가 하고, 상품번호는 {@code lemuel.product.registered} 로 돌아온다.
 * 그 사이 창에서 신청서는 "승인됨, 상품번호 대기" 이며 화면은 그걸 그대로 보여 준다.
 *
 * <p>이 서비스가 카탈로그에 직접 쓰지 않는 이유는 상품 원장의 소유자가 order-service 이기
 * 때문이다. 여기서 직접 쓰면 DB-per-service 는 이름만 남고, 상품 상태의 정본이 둘이 된다.
 */
public interface ReviewProductSubmissionUseCase {

    /** 승인. 상태 변경과 발행이 같은 트랜잭션에서 커밋된다(Transactional Outbox). */
    SubmissionView approve(long submissionId, long operatorUserId);

    /**
     * 반려. 사유가 비어 있으면 거절한다.
     *
     * <p>사유 없는 반려는 셀러에게 아무 정보도 주지 않는다. 레퍼런스에서는 사유가 선택이었고,
     * 그 결과 "반려됨" 만 받은 셀러가 같은 내용을 그대로 다시 올리는 일이 반복됐다.
     */
    SubmissionView reject(long submissionId, long operatorUserId, String reason);
}
