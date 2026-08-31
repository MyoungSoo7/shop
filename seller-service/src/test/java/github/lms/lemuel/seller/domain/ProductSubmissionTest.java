package github.lms.lemuel.seller.domain;

import github.lms.lemuel.seller.domain.exception.IllegalSubmissionStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 신청서의 다섯 전이가 전부 여기에 있고 다른 어디에도 없다 — 그 사실을 고정한다.
 *
 * <p>서비스 계층 테스트에서 전이를 다시 검증하지 않는 이유이기도 하다. 규칙이 두 군데서 검사되면
 * 한쪽을 고칠 때 다른 쪽이 남고, 그러면 어느 쪽이 진짜 규칙인지 코드가 말해 주지 않는다.
 */
class ProductSubmissionTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    private static ProductContent content(String name) {
        return new ProductContent(name, "설명", new BigDecimal("12900"), 10, "식품", null, true);
    }

    private static ProductSubmission draft() {
        return ProductSubmission.draft(777L, 7L, 42L, SubmissionType.NEW, null, content("사과 1kg"))
                .withId(1L);
    }

    private static ProductSubmission submitted() {
        return draft().submit(NOW);
    }

    // ------------------------------------------------------------------ 생성

    @Test
    void 신규_신청서는_DRAFT_로_시작하고_번호가_없다() {
        ProductSubmission draft = ProductSubmission.draft(
                777L, 7L, 42L, SubmissionType.NEW, null, content("사과 1kg"));

        assertNull(draft.submissionId());
        assertEquals(SubmissionStatus.DRAFT, draft.status());
        assertNull(draft.productId());
        assertFalse(draft.awaitingCatalog());
    }

    @Test
    void 저장_전_신청서의_번호를_요구하면_던진다() {
        // null 을 흘려보내면 outbox 의 aggregate_id 가 "null" 문자열이 되고, 그 이벤트는
        // 파티션 키가 깨진 채 발행된다 — 발행 자체는 성공하므로 아무 신호도 나지 않는다.
        assertThrows(IllegalStateException.class,
                () -> ProductSubmission.draft(777L, 7L, 42L, SubmissionType.NEW, null, content("사과"))
                        .requireSubmissionId());
    }

    @Test
    void 수정_신청은_대상_상품번호가_있어야_한다() {
        // V1 의 chk_submission_base_product 와 같은 규칙. DB 제약만 두면 INSERT 시점에야 터지고
        // 그때는 어느 화면에서 왔는지 스택 트레이스가 말해 주지 않는다.
        assertThrows(IllegalArgumentException.class,
                () -> ProductSubmission.draft(777L, 7L, 42L, SubmissionType.UPDATE, null, content("사과")));
    }

    // ------------------------------------------------------------------ 수정

    @Test
    void 작성_중에는_내용을_고칠_수_있다() {
        ProductSubmission updated = draft().withContent(content("사과 2kg"));

        assertEquals("사과 2kg", updated.content().name());
        assertEquals(SubmissionStatus.DRAFT, updated.status());
    }

    @Test
    void 반려된_건도_고칠_수_있다() {
        ProductSubmission rejected = submitted().reject(9L, "이미지 누락", NOW);

        // 레퍼런스에서는 반려되면 처음부터 다시 등록해야 했고, 그래서 같은 상품이 반려 이력만
        // 남긴 채 여러 건으로 늘어났다. 여기서는 한 건이 고쳐져 다시 올라간다.
        ProductSubmission fixed = rejected.withContent(content("사과 1kg (이미지 추가)"));
        assertEquals(SubmissionStatus.REJECTED, fixed.status());
        assertEquals("이미지 누락", fixed.rejectReason());
    }

    @Test
    void 심사_중이거나_승인된_건은_못_고친다() {
        assertThrows(IllegalSubmissionStateException.class,
                () -> submitted().withContent(content("몰래 바꾼 이름")));
        assertThrows(IllegalSubmissionStateException.class,
                () -> submitted().approve(9L, NOW).withContent(content("몰래 바꾼 이름")));
    }

    // ------------------------------------------------------------------ 제출

    @Test
    void 제출하면_SUBMITTED_가_되고_이전_반려사유가_지워진다() {
        ProductSubmission resubmitted = submitted().reject(9L, "이미지 누락", NOW).submit(NOW);

        assertEquals(SubmissionStatus.SUBMITTED, resubmitted.status());
        // 새 심사인데 이전 사유가 남아 있으면 심사자 화면이 "반려된 건이 대기열에 있다" 로 보인다.
        assertNull(resubmitted.rejectReason());
        assertNull(resubmitted.decidedAt());
        assertNull(resubmitted.decidedByUserId());
        assertEquals(NOW, resubmitted.submittedAt());
    }

    @Test
    void 이미_제출된_건은_다시_제출할_수_없다() {
        assertThrows(IllegalSubmissionStateException.class, () -> submitted().submit(NOW));
    }

    // ------------------------------------------------------------------ 심사

    @Test
    void 승인해도_상품번호는_아직_없다() {
        ProductSubmission approved = submitted().approve(9L, NOW);

        assertEquals(SubmissionStatus.APPROVED, approved.status());
        assertNull(approved.productId());
        // 화면은 이 상태를 "등록 처리 중" 으로 보여 준다. 승인 즉시 완료로 표시하면 등록이
        // 실패한 건과 몇 초 뒤 성공할 건이 구분되지 않는다.
        assertTrue(approved.awaitingCatalog());
        assertEquals(9L, approved.decidedByUserId());
        assertEquals(NOW, approved.decidedAt());
    }

    @Test
    void 제출되지_않은_건은_승인도_반려도_할_수_없다() {
        assertThrows(IllegalSubmissionStateException.class, () -> draft().approve(9L, NOW));
        assertThrows(IllegalSubmissionStateException.class, () -> draft().reject(9L, "사유", NOW));
    }

    @Test
    void 사유_없는_반려는_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> submitted().reject(9L, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> submitted().reject(9L, "   ", NOW));
    }

    @Test
    void 반려_사유는_500자를_넘을_수_없다() {
        String tooLong = "가".repeat(ProductSubmission.MAX_REJECT_REASON_LENGTH + 1);

        // 자르지 않고 거절한다. 조용히 자르면 셀러가 보는 사유의 끝이 사라져 무엇을 고쳐야 하는지
        // 알 수 없게 되고, 그 사실은 아무 데도 기록되지 않는다.
        assertThrows(IllegalArgumentException.class, () -> submitted().reject(9L, tooLong, NOW));
    }

    @Test
    void 반려_사유는_공백을_다듬어_보관한다() {
        assertEquals("이미지 누락", submitted().reject(9L, "  이미지 누락  ", NOW).rejectReason());
    }

    // ------------------------------------------------------------------ 회신

    @Test
    void 카탈로그_회신이_오면_상품번호가_붙는다() {
        ProductSubmission registered = submitted().approve(9L, NOW).catalogRegistered(5001L);

        assertEquals(5001L, registered.productId());
        assertEquals(SubmissionStatus.APPROVED, registered.status());
        assertFalse(registered.awaitingCatalog());
    }

    @Test
    void 승인되지_않은_건에는_상품번호를_붙이지_않는다() {
        // 이건 재전송이 아니라 뒤엉킨 이벤트다. 삼키면 심사되지 않은 상품이 몰에 걸린다.
        assertThrows(IllegalSubmissionStateException.class, () -> submitted().catalogRegistered(5001L));
        assertThrows(IllegalSubmissionStateException.class,
                () -> submitted().reject(9L, "사유", NOW).catalogRegistered(5001L));
    }

    // ------------------------------------------------------------------ 내용 검증

    @Test
    void 상품명은_필수이고_300자를_넘을_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> content(null));
        assertThrows(IllegalArgumentException.class, () -> content("   "));
        assertThrows(IllegalArgumentException.class,
                () -> content("가".repeat(ProductContent.MAX_NAME_LENGTH + 1)));
    }

    @Test
    void 음수_가격과_음수_재고는_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductContent("사과", null, new BigDecimal("-1"), 0, null, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductContent("사과", null, BigDecimal.ZERO, -1, null, null, true));
    }

    @Test
    void 영원_영재고는_허용한다() {
        // 사은품·체험판이 실제로 있고, 재고 0 으로 등록한 뒤 채우는 것이 정상 흐름이다.
        ProductContent free = new ProductContent("체험판", null, BigDecimal.ZERO, 0, null, null, true);

        assertEquals(0, free.price().signum());
        assertEquals(0, free.stock());
    }

    @Test
    void 빈_문자열_선택항목은_null_로_다듬는다() {
        ProductContent trimmed = new ProductContent(
                "  사과  ", "   ", new BigDecimal("100"), 1, "", "  ", false);

        assertEquals("사과", trimmed.name());
        // "" 와 null 을 섞어 두면 화면이 빈 칸과 미입력을 구분하지 못하고, 검색 조건에서도
        // 같은 값이 두 가지로 저장된다.
        assertNull(trimmed.description());
        assertNull(trimmed.category());
        assertNull(trimmed.imageUrl());
    }
}
