package github.lms.lemuel.seller.domain;

/**
 * 상품 등록 신청서의 상태 — 레퍼런스(ssgb2e-outbackoffice)의 {@code PRST} 를 옮긴 것.
 *
 * <p>매핑: {@code PRST=2} 대기 → {@link #SUBMITTED}, {@code PRST=1} 판매중 → {@link #APPROVED},
 * {@code PRST=3} 반려 → {@link #REJECTED}. {@link #DRAFT} 는 레퍼런스에 없다 — 거기서는 등록
 * 화면에서 저장을 누르는 즉시 대기 상태가 됐고, 그래서 쓰다 만 신청서가 심사 대기열에 섞였다.
 *
 * <p><b>APPROVED 는 "판매중" 이 아니다.</b> 레퍼런스에서는 같은 값이었지만 여기서는 다르다.
 * 승인은 이 서비스의 판단이고, 실제 카탈로그 등록은 order-service 가 한다. 그 사이에 창이
 * 있고(요청 발행 → 수신 → 등록 → product.registered 회신), 그 창에서 신청서는 APPROVED 이면서
 * {@code productId} 가 없다. 화면은 그 상태를 "등록 처리 중" 으로 보여 준다.
 */
public enum SubmissionStatus {

    /** 셀러가 쓰는 중. 심사 대기열에 나타나지 않는다. */
    DRAFT,

    /** 제출됨 — 운영자 심사 대기. */
    SUBMITTED,

    /** 승인됨. 카탈로그 등록 요청이 나갔고, 상품번호는 회신을 기다린다. */
    APPROVED,

    /** 반려됨. 사유가 반드시 함께 있다(V1 마이그레이션의 CHECK 제약). */
    REJECTED;

    /** 제출할 수 있는 상태인가 — 작성 중이거나, 반려돼서 고친 뒤다. */
    public boolean submittable() {
        return this == DRAFT || this == REJECTED;
    }

    /** 셀러가 내용을 고칠 수 있는 상태인가. 심사 중·승인 후에는 못 고친다. */
    public boolean editable() {
        return this == DRAFT || this == REJECTED;
    }
}
