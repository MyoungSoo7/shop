package github.lms.lemuel.inquiry.domain;

import github.lms.lemuel.inquiry.domain.exception.InquiryAlreadyAnsweredException;
import github.lms.lemuel.inquiry.domain.exception.InquiryInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 문의 도메인 규칙.
 *
 * <p>여기서 검증하는 것들은 대부분 레거시(ssg-front)에 <b>없었던</b> 검사다. 없어서 무슨 일이
 * 생겼는지는 각 테스트 이름과 {@link Inquiry} javadoc 에 적어 두었다.
 */
@DisplayName("문의 도메인")
class InquiryDomainTest {

    private static final LocalDateTime ASKED_AT = LocalDateTime.of(2026, 8, 27, 10, 0);

    private static Inquiry product(boolean secret) {
        return Inquiry.ask(7L, InquiryType.PRODUCT, 100L, null, "사이즈 문의", "정사이즈인가요?", secret, ASKED_AT);
    }

    private static InquiryAnswer answer() {
        return new InquiryAnswer(1L, 900L, "정사이즈입니다.", ASKED_AT.plusHours(1));
    }

    @Nested
    @DisplayName("종류가 요구하는 대상")
    class Target {

        @Test
        @DisplayName("상품 문의에 상품이 없으면 만들어지지 않는다 — 레거시는 그대로 저장해 답할 수 없는 문의가 남았다")
        void productInquiryRequiresProduct() {
            assertThatThrownBy(() ->
                    Inquiry.ask(7L, InquiryType.PRODUCT, null, null, "제목", "본문", false, ASKED_AT))
                    .isInstanceOf(InquiryInvariantViolationException.class)
                    .hasMessageContaining("대상 상품");
        }

        @Test
        @DisplayName("주문 문의에 주문이 없으면 만들어지지 않는다")
        void orderInquiryRequiresOrder() {
            assertThatThrownBy(() ->
                    Inquiry.ask(7L, InquiryType.ORDER, null, null, "제목", "본문", false, ASKED_AT))
                    .isInstanceOf(InquiryInvariantViolationException.class)
                    .hasMessageContaining("대상 주문");
        }

        @Test
        @DisplayName("1:1 문의는 상품도 주문도 요구하지 않는다")
        void generalRequiresNeither() {
            Inquiry inquiry = Inquiry.ask(7L, InquiryType.GENERAL, null, null, "제목", "본문", false, ASKED_AT);

            assertThat(inquiry.productId()).isNull();
            assertThat(inquiry.orderId()).isNull();
            assertThat(InquiryType.GENERAL.requiresProduct()).isFalse();
            assertThat(InquiryType.GENERAL.requiresOrder()).isFalse();
        }
    }

    @Nested
    @DisplayName("제목·본문")
    class Text {

        @Test
        @DisplayName("공백만 있는 제목은 빈 제목이다")
        void blankSubjectRejected() {
            assertThatThrownBy(() ->
                    Inquiry.ask(7L, InquiryType.GENERAL, null, null, "   ", "본문", false, ASKED_AT))
                    .isInstanceOf(InquiryInvariantViolationException.class)
                    .hasMessageContaining("제목");
        }

        @Test
        @DisplayName("앞뒤 공백은 잘라서 저장한다")
        void textIsTrimmed() {
            Inquiry inquiry = Inquiry.ask(7L, InquiryType.GENERAL, null, null,
                    "  제목  ", "  본문  ", false, ASKED_AT);

            assertThat(inquiry.subject()).isEqualTo("제목");
            assertThat(inquiry.content()).isEqualTo("본문");
        }

        @Test
        @DisplayName("길이 한도를 넘기면 몇 자인지 알려 준다")
        void tooLongRejected() {
            String tooLong = "가".repeat(Inquiry.SUBJECT_MAX + 1);

            assertThatThrownBy(() ->
                    Inquiry.ask(7L, InquiryType.GENERAL, null, null, tooLong, "본문", false, ASKED_AT))
                    .isInstanceOf(InquiryInvariantViolationException.class)
                    .hasMessageContaining(String.valueOf(Inquiry.SUBJECT_MAX + 1));
        }
    }

    @Nested
    @DisplayName("답변 상태는 저장하지 않고 계산한다")
    class Status {

        @Test
        @DisplayName("답변이 없으면 대기")
        void waitingWithoutAnswers() {
            assertThat(product(false).status()).isEqualTo(InquiryStatus.WAITING);
            assertThat(product(false).isAnswered()).isFalse();
        }

        @Test
        @DisplayName("답변을 붙이면 그 순간 완료")
        void answeredWithAnswer() {
            Inquiry answered = product(false).withAnswer(answer());

            assertThat(answered.status()).isEqualTo(InquiryStatus.ANSWERED);
            assertThat(answered.answers()).hasSize(1);
        }

        @Test
        @DisplayName("답변을 뺀 사본은 곧바로 다시 대기 — 레거시는 상태 칼럼을 되돌리지 않아 목록만 '답변완료'로 남았다")
        void backToWaitingWhenAnswersGone() {
            Inquiry answered = product(false).withAnswer(answer());

            Inquiry withoutAnswers = new Inquiry(answered.id(), answered.userId(), answered.type(),
                    answered.productId(), answered.orderId(), answered.subject(), answered.content(),
                    answered.secret(), answered.askedAt(), List.of());

            assertThat(withoutAnswers.status()).isEqualTo(InquiryStatus.WAITING);
        }

        @Test
        @DisplayName("withAnswer 는 원본을 건드리지 않는다")
        void withAnswerDoesNotMutateSource() {
            Inquiry original = product(false);

            original.withAnswer(answer());

            assertThat(original.answers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("답변 뒤 수정 금지")
    class Editability {

        @Test
        @DisplayName("답변 전에는 제목·본문·공개 여부를 고칠 수 있다")
        void editableBeforeAnswer() {
            Inquiry edited = product(false).edit("새 제목", "새 본문", true);

            assertThat(edited.subject()).isEqualTo("새 제목");
            assertThat(edited.secret()).isTrue();
        }

        @Test
        @DisplayName("종류와 대상은 수정으로 바뀌지 않는다")
        void typeAndTargetSurviveEdit() {
            Inquiry edited = product(false).edit("새 제목", "새 본문", false);

            assertThat(edited.type()).isEqualTo(InquiryType.PRODUCT);
            assertThat(edited.productId()).isEqualTo(100L);
            assertThat(edited.askedAt()).isEqualTo(ASKED_AT);
        }

        @Test
        @DisplayName("답변이 달린 뒤 수정하면 거부 — 레거시엔 이 검사가 없어 안 맞는 질문·답 한 쌍이 남았다")
        void notEditableAfterAnswer() {
            Inquiry answered = product(false).withAnswer(answer());

            assertThatThrownBy(() -> answered.edit("바꾼 제목", "바꾼 본문", false))
                    .isInstanceOf(InquiryAlreadyAnsweredException.class);
            assertThatThrownBy(answered::requireEditable)
                    .isInstanceOf(InquiryAlreadyAnsweredException.class);
        }
    }

    @Nested
    @DisplayName("공개 범위")
    class Visibility {

        @Test
        @DisplayName("공개된 상품 문의만 상품 페이지에 본문까지 걸린다")
        void onlyPublicProductInquiryIsListed() {
            assertThat(product(false).publiclyListed()).isTrue();
            assertThat(product(true).publiclyListed()).isFalse();
        }

        @Test
        @DisplayName("주문·1:1 문의는 공개 목록 자체가 없다")
        void nonProductNeverListed() {
            Inquiry general = Inquiry.ask(7L, InquiryType.GENERAL, null, null, "제목", "본문", false, ASKED_AT);
            Inquiry order = Inquiry.ask(7L, InquiryType.ORDER, null, 55L, "제목", "본문", false, ASKED_AT);

            assertThat(general.publiclyListed()).isFalse();
            assertThat(order.publiclyListed()).isFalse();
        }

        @Test
        @DisplayName("비밀글은 작성자와 관리자만 읽는다")
        void secretReadableByOwnerAndAdmin() {
            Inquiry secret = product(true);

            assertThat(secret.isReadableBy(7L, false)).isTrue();     // 작성자
            assertThat(secret.isReadableBy(99L, true)).isTrue();     // 관리자
            assertThat(secret.isReadableBy(99L, false)).isFalse();   // 남
            assertThat(secret.isReadableBy(null, false)).isFalse();  // 비로그인
        }

        @Test
        @DisplayName("작성자 판정은 null 로 통과하지 않는다")
        void ownershipNotSatisfiedByNull() {
            Inquiry inquiry = new Inquiry(1L, 7L, InquiryType.GENERAL, null, null,
                    "제목", "본문", false, ASKED_AT, List.of());

            assertThat(inquiry.isOwnedBy(null)).isFalse();
            assertThat(inquiry.isOwnedBy(7L)).isTrue();
        }
    }

    @Nested
    @DisplayName("답변 값 객체")
    class Answer {

        @Test
        @DisplayName("빈 답변은 만들어지지 않는다")
        void blankAnswerRejected() {
            assertThatThrownBy(() -> InquiryAnswer.of(900L, "  ", ASKED_AT))
                    .isInstanceOf(InquiryInvariantViolationException.class);
        }

        @Test
        @DisplayName("아직 저장 전인 답변은 id 가 없다")
        void newAnswerHasNoId() {
            InquiryAnswer created = InquiryAnswer.of(900L, "답변합니다.", ASKED_AT);

            assertThat(created.id()).isNull();
            assertThat(created.answeredBy()).isEqualTo(900L);
        }
    }
}
