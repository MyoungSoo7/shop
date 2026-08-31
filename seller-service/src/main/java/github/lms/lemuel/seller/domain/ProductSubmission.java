package github.lms.lemuel.seller.domain;

import github.lms.lemuel.seller.domain.exception.IllegalSubmissionStateException;
import java.time.OffsetDateTime;

/**
 * 상품 등록 신청서 — 이 서비스가 <b>원본을 소유하는</b> 두 애그리거트 중 하나.
 *
 * <p>partner-service 는 전부 남의 이벤트를 베껴 온 사본이라 상태 전이가 없었다. 여기는 다르다.
 * 셀러가 쓰고, 제출하고, 운영자가 승인하거나 반려하고, 카탈로그 등록이 끝나면 상품번호가 붙는다.
 * 그 다섯 개의 전이가 이 클래스에 전부 있고, 다른 어디에도 없다.
 *
 * <h2>왜 record 이고 왜 매번 새 인스턴스인가</h2>
 * 전이 메서드가 {@code this} 를 고치는 대신 새 값을 돌려주면, 서비스 계층에서 "저장을 깜빡해서
 * 전이가 사라지는" 실수가 컴파일 단계에서 눈에 띈다 — 반환값을 안 쓰면 그 줄이 아무 일도 안
 * 하는 게 명백하기 때문이다. 가변 엔티티였다면 같은 실수가 런타임에만 드러난다.
 *
 * <h2>APPROVED 와 productId 는 별개다</h2>
 * {@link #approve} 는 상태만 바꾼다. 상품번호는 order-service 가 카탈로그에 실제로 넣은 뒤
 * {@code lemuel.product.registered} 로 회신해야 {@link #catalogRegistered} 로 채워진다.
 * 둘을 한 번에 처리하고 싶은 유혹이 있지만, 그러면 "승인은 됐는데 카탈로그 등록이 실패한"
 * 상태를 표현할 수 없게 된다 — 그 상태는 실제로 생기고, 운영자가 봐야 하는 상태다.
 *
 * @param submissionId 저장 전에는 {@code null}. 저장 후 채워진다.
 * @param sellerId 이 신청서를 낸 셀러. 조직이 아니라 셀러 번호다 — 주문·매출과 같은 축.
 * @param organizationId 셀러가 속한 조직. 감사 추적용.
 * @param createdByUserId 실제로 작성한 사람. 조직 안에서 누가 냈는지 남긴다.
 * @param type 신규인가 수정인가.
 * @param baseProductId {@link SubmissionType#UPDATE} 일 때 수정 대상 상품. NEW 면 {@code null}.
 * @param content 상품 내용.
 * @param status 현재 상태.
 * @param rejectReason 반려 사유. {@link SubmissionStatus#REJECTED} 일 때만 값이 있다.
 * @param productId 카탈로그 등록이 끝난 뒤의 상품번호. 그 전까지 {@code null}.
 * @param submittedAt 제출 시각.
 * @param decidedAt 승인·반려 시각.
 * @param decidedByUserId 승인·반려한 운영자.
 */
public record ProductSubmission(
        Long submissionId,
        long sellerId,
        long organizationId,
        long createdByUserId,
        SubmissionType type,
        Long baseProductId,
        ProductContent content,
        SubmissionStatus status,
        String rejectReason,
        Long productId,
        OffsetDateTime submittedAt,
        OffsetDateTime decidedAt,
        Long decidedByUserId) {

    /** 반려 사유 상한 — V1 마이그레이션의 {@code VARCHAR(500)} 과 짝이다. */
    public static final int MAX_REJECT_REASON_LENGTH = 500;

    public ProductSubmission {
        if (type == null) {
            throw new IllegalArgumentException("신청 유형은 필수입니다.");
        }
        if (status == null) {
            throw new IllegalArgumentException("신청 상태는 필수입니다.");
        }
        if (content == null) {
            throw new IllegalArgumentException("상품 내용은 필수입니다.");
        }
        if (type == SubmissionType.UPDATE && baseProductId == null) {
            // V1 의 chk_submission_base_product 와 같은 규칙. DB 제약만 두면 위반이 INSERT
            // 시점에야 터지고, 그때는 스택 트레이스가 어느 화면에서 왔는지 말해 주지 않는다.
            throw new IllegalArgumentException("수정 신청은 대상 상품번호가 있어야 합니다.");
        }
        if (status == SubmissionStatus.REJECTED && (rejectReason == null || rejectReason.isBlank())) {
            throw new IllegalArgumentException("반려에는 사유가 필요합니다.");
        }
    }

    /** 새 신청서를 작성 중(DRAFT) 상태로 만든다. */
    public static ProductSubmission draft(
            long sellerId,
            long organizationId,
            long createdByUserId,
            SubmissionType type,
            Long baseProductId,
            ProductContent content) {
        return new ProductSubmission(
                null,
                sellerId,
                organizationId,
                createdByUserId,
                type,
                baseProductId,
                content,
                SubmissionStatus.DRAFT,
                null,
                null,
                null,
                null,
                null);
    }

    /** 저장 직후 채번된 번호를 붙인다. 영속화 어댑터만 쓴다. */
    public ProductSubmission withId(long assignedId) {
        return new ProductSubmission(
                assignedId, sellerId, organizationId, createdByUserId, type, baseProductId,
                content, status, rejectReason, productId, submittedAt, decidedAt, decidedByUserId);
    }

    /**
     * 내용을 고친다. 작성 중이거나 반려된 건만 고칠 수 있다.
     *
     * <p>반려된 건을 고칠 수 있게 둔 것은 의도다. 레퍼런스에서는 반려되면 처음부터 다시
     * 등록해야 했고, 그래서 같은 상품이 반려 이력만 남긴 채 여러 건으로 늘어났다. 여기서는
     * 한 건이 고쳐져 다시 올라간다 — 심사자가 "무엇을 고쳤는지" 를 한 줄에서 본다.
     */
    public ProductSubmission withContent(ProductContent updated) {
        if (!status.editable()) {
            throw new IllegalSubmissionStateException(status, "내용 수정을");
        }
        return new ProductSubmission(
                submissionId, sellerId, organizationId, createdByUserId, type, baseProductId,
                updated, status, rejectReason, productId, submittedAt, decidedAt, decidedByUserId);
    }

    /** 심사에 올린다. 반려 사유는 지운다 — 새 심사이므로 이전 사유가 남아 있으면 화면이 거짓말한다. */
    public ProductSubmission submit(OffsetDateTime now) {
        if (!status.submittable()) {
            throw new IllegalSubmissionStateException(status, "제출을");
        }
        return new ProductSubmission(
                submissionId, sellerId, organizationId, createdByUserId, type, baseProductId,
                content, SubmissionStatus.SUBMITTED, null, productId, now, null, null);
    }

    /** 승인한다. 상태만 바뀌고 상품번호는 아직 없다 — 클래스 주석의 "APPROVED 와 productId" 참고. */
    public ProductSubmission approve(long operatorUserId, OffsetDateTime now) {
        if (status != SubmissionStatus.SUBMITTED) {
            throw new IllegalSubmissionStateException(status, "승인을");
        }
        return new ProductSubmission(
                submissionId, sellerId, organizationId, createdByUserId, type, baseProductId,
                content, SubmissionStatus.APPROVED, null, productId, submittedAt, now, operatorUserId);
    }

    /** 반려한다. 사유가 없으면 거절 — 사유 없는 반려는 셀러에게 아무 정보도 주지 않는다. */
    public ProductSubmission reject(long operatorUserId, String reason, OffsetDateTime now) {
        if (status != SubmissionStatus.SUBMITTED) {
            throw new IllegalSubmissionStateException(status, "반려를");
        }
        String trimmed = reason == null ? null : reason.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new IllegalArgumentException("반려 사유를 입력해 주세요.");
        }
        if (trimmed.length() > MAX_REJECT_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "반려 사유는 " + MAX_REJECT_REASON_LENGTH + "자 이하여야 합니다 (입력 " + trimmed.length() + "자).");
        }
        return new ProductSubmission(
                submissionId, sellerId, organizationId, createdByUserId, type, baseProductId,
                content, SubmissionStatus.REJECTED, trimmed, productId, submittedAt, now, operatorUserId);
    }

    /**
     * order-service 가 카탈로그 등록을 마쳤다는 회신을 반영한다.
     *
     * <p>APPROVED 가 아닌 건에는 붙이지 않는다. 이 회신은 Kafka 로 오고, at-least-once 라서
     * 같은 이벤트가 두 번 올 수 있다 — 두 번째는 이미 상품번호가 붙어 있어도 같은 값이므로
     * 조용히 덮어써도 무방하지만, 상태가 APPROVED 가 아니라면 그건 재전송이 아니라 뒤엉킨
     * 이벤트다. 그런 건 삼키지 않고 예외로 드러낸다.
     */
    public ProductSubmission catalogRegistered(long registeredProductId) {
        if (status != SubmissionStatus.APPROVED) {
            throw new IllegalSubmissionStateException(status, "카탈로그 등록 반영을");
        }
        return new ProductSubmission(
                submissionId, sellerId, organizationId, createdByUserId, type, baseProductId,
                content, status, rejectReason, registeredProductId, submittedAt, decidedAt, decidedByUserId);
    }

    /** 저장된 신청서의 번호. 아직 저장 전이면 예외 — {@code null} 을 흘려보내지 않는다. */
    public long requireSubmissionId() {
        if (submissionId == null) {
            throw new IllegalStateException("아직 저장되지 않은 신청서입니다.");
        }
        return submissionId;
    }

    /** 승인은 났지만 카탈로그 등록 회신을 아직 못 받은 상태인가 — 화면의 "등록 처리 중". */
    public boolean awaitingCatalog() {
        return status == SubmissionStatus.APPROVED && productId == null;
    }
}
