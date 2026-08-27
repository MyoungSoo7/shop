package github.lms.lemuel.inquiry.application.service;

import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.inquiry.application.port.out.LoadInquiryPort;
import github.lms.lemuel.inquiry.application.port.out.NotifyInquiryPort;
import github.lms.lemuel.inquiry.application.port.out.SaveInquiryPort;
import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import github.lms.lemuel.inquiry.domain.InquiryStatus;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.inquiry.domain.exception.InquiryAlreadyAnsweredException;
import github.lms.lemuel.inquiry.domain.exception.InquiryAnswerNotFoundException;
import github.lms.lemuel.inquiry.domain.exception.InquiryInvariantViolationException;
import github.lms.lemuel.inquiry.domain.exception.InquiryNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 문의 서비스.
 *
 * <p>레거시에서 사고가 났던 지점을 그대로 테스트로 옮겼다 — 알림 실패를 등록 실패로 보고하던 것,
 * 소유권 대조 없이 상세를 열던 것, 답변 뒤 수정이 열려 있던 것, 답변 번호만 보고 지우던 것.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("문의 서비스")
class InquiryServiceTest {

    private static final Long OWNER = 7L;
    private static final Long STRANGER = 99L;
    private static final Long ADMIN = 900L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

    private final Clock clock = Clock.fixed(
            NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

    @Mock private LoadInquiryPort loadInquiryPort;
    @Mock private SaveInquiryPort saveInquiryPort;
    @Mock private NotifyInquiryPort notifyInquiryPort;

    private InquiryService service() {
        return new InquiryService(loadInquiryPort, saveInquiryPort, notifyInquiryPort, clock);
    }

    private static Inquiry stored(Long id, Long userId, boolean secret, List<InquiryAnswer> answers) {
        return new Inquiry(id, userId, InquiryType.PRODUCT, 100L, null,
                "사이즈 문의", "정사이즈인가요?", secret, NOW, answers);
    }

    private static InquiryAnswer answer(Long id) {
        return new InquiryAnswer(id, ADMIN, "정사이즈입니다.", NOW.plusHours(1));
    }

    private static InquiryUseCase.AskCommand askCommand() {
        return new InquiryUseCase.AskCommand(OWNER, InquiryType.PRODUCT, 100L, null,
                "사이즈 문의", "정사이즈인가요?", false);
    }

    @Nested
    @DisplayName("등록")
    class Ask {

        @Test
        @DisplayName("작성 시각은 주입된 Clock 이 정한다")
        void usesClock() {
            when(saveInquiryPort.save(any())).thenAnswer(i -> i.getArgument(0));

            service().ask(askCommand());

            ArgumentCaptor<Inquiry> captor = ArgumentCaptor.forClass(Inquiry.class);
            verify(saveInquiryPort).save(captor.capture());
            assertThat(captor.getValue().askedAt()).isEqualTo(NOW);
            assertThat(captor.getValue().userId()).isEqualTo(OWNER);
            assertThat(captor.getValue().id()).isNull();
        }

        @Test
        @DisplayName("알림이 터져도 등록은 성공이다 — 레거시는 같은 try 안이라 '등록 실패'로 보고했다")
        void notifyFailureDoesNotFailAsk() {
            Inquiry saved = stored(1L, OWNER, false, List.of());
            when(saveInquiryPort.save(any())).thenReturn(saved);
            doThrow(new RuntimeException("알림톡 게이트웨이 5xx"))
                    .when(notifyInquiryPort).notifyAsked(any());

            assertThat(service().ask(askCommand())).isEqualTo(saved);
        }

        @Test
        @DisplayName("답변 알림이 터져도 답변 등록은 성공이다")
        void notifyFailureDoesNotFailAnswer() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));
            when(saveInquiryPort.addAnswer(eq(1L), any())).thenReturn(answer(10L));
            doThrow(new RuntimeException("알림톡 게이트웨이 5xx"))
                    .when(notifyInquiryPort).notifyAnswered(any());

            assertThat(service().answer(1L, ADMIN, "정사이즈입니다.").status())
                    .isEqualTo(InquiryStatus.ANSWERED);
        }

        @Test
        @DisplayName("알림이 조용히 성공하면 저장된 문의가 그대로 돌아온다")
        void returnsSaved() {
            Inquiry saved = stored(1L, OWNER, false, List.of());
            when(saveInquiryPort.save(any())).thenReturn(saved);

            assertThat(service().ask(askCommand())).isEqualTo(saved);
            verify(notifyInquiryPort).notifyAsked(saved);
        }

        @Test
        @DisplayName("종류가 없으면 저장까지 가지 않는다")
        void typeRequired() {
            InquiryUseCase.AskCommand noType = new InquiryUseCase.AskCommand(
                    OWNER, null, 100L, null, "제목", "본문", false);

            assertThatThrownBy(() -> service().ask(noType))
                    .isInstanceOf(InquiryInvariantViolationException.class);
            verify(saveInquiryPort, never()).save(any());
        }

        @Test
        @DisplayName("작성자가 없으면 저장까지 가지 않는다")
        void userRequired() {
            InquiryUseCase.AskCommand noUser = new InquiryUseCase.AskCommand(
                    null, InquiryType.GENERAL, null, null, "제목", "본문", false);

            assertThatThrownBy(() -> service().ask(noUser))
                    .isInstanceOf(InquiryInvariantViolationException.class);
            verify(saveInquiryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("없는 문의는 404 — 레거시는 결과를 확인하지 않고 필드를 꺼내 NPE 로 500 이 났다")
        void missingIsNotFound() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().get(1L, OWNER, false))
                    .isInstanceOf(InquiryNotFoundException.class);
        }

        @Test
        @DisplayName("남의 비밀 문의는 못 읽는다")
        void strangerCannotReadSecret() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, true, List.of())));

            assertThatThrownBy(() -> service().get(1L, STRANGER, false))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("관리자는 비밀 문의도 읽는다 — 답하려면 읽어야 한다")
        void adminReadsSecret() {
            Inquiry secret = stored(1L, OWNER, true, List.of());
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(secret));

            assertThat(service().get(1L, ADMIN, true)).isEqualTo(secret);
        }

        @Test
        @DisplayName("공개된 상품 문의는 아무나 읽는다")
        void publicProductInquiryIsOpen() {
            Inquiry open = stored(1L, OWNER, false, List.of());
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(open));

            assertThat(service().get(1L, STRANGER, false)).isEqualTo(open);
        }

        @Test
        @DisplayName("내 문의 목록은 한도를 넘겨 읽지 않는다")
        void listMineIsBounded() {
            when(loadInquiryPort.findByUserId(OWNER, null, InquiryUseCase.MAX_LIST_SIZE))
                    .thenReturn(List.of(stored(1L, OWNER, false, List.of())));

            assertThat(service().listMine(OWNER, null)).hasSize(1);
            verify(loadInquiryPort).findByUserId(OWNER, null, InquiryUseCase.MAX_LIST_SIZE);
        }

        @Test
        @DisplayName("종류를 주면 그대로 포트에 넘긴다")
        void listMineFiltersByType() {
            when(loadInquiryPort.findByUserId(OWNER, InquiryType.ORDER, InquiryUseCase.MAX_LIST_SIZE))
                    .thenReturn(List.of());

            service().listMine(OWNER, InquiryType.ORDER);

            verify(loadInquiryPort).findByUserId(OWNER, InquiryType.ORDER, InquiryUseCase.MAX_LIST_SIZE);
        }
    }

    @Nested
    @DisplayName("상품 문의 목록의 가림")
    class Masking {

        @Test
        @DisplayName("못 읽는 문의는 빼지 않고 가린다 — 개수가 보는 사람마다 달라지면 안 된다")
        void masksInsteadOfFiltering() {
            when(loadInquiryPort.findByProductId(100L, InquiryUseCase.MAX_PRODUCT_LIST_SIZE))
                    .thenReturn(List.of(
                            stored(1L, OWNER, false, List.of()),
                            stored(2L, OWNER, true, List.of(answer(10L)))));

            List<Inquiry> listed = service().listForProduct(100L, STRANGER, false);

            assertThat(listed).hasSize(2);
            assertThat(listed.get(0).subject()).isEqualTo("사이즈 문의");
            assertThat(listed.get(1).subject()).isEqualTo(Inquiry.MASKED_SUBJECT);
        }

        @Test
        @DisplayName("가릴 때 답변 본문도 함께 가린다 — 질문만 가리면 아무 소용이 없다")
        void masksAnswerContentToo() {
            when(loadInquiryPort.findByProductId(100L, InquiryUseCase.MAX_PRODUCT_LIST_SIZE))
                    .thenReturn(List.of(stored(2L, OWNER, true, List.of(answer(10L)))));

            List<Inquiry> listed = service().listForProduct(100L, STRANGER, false);

            assertThat(listed.get(0).answers()).hasSize(1);
            assertThat(listed.get(0).answers().get(0).content()).isNotEqualTo("정사이즈입니다.");
            assertThat(listed.get(0).answers().get(0).id()).isEqualTo(10L);
        }

        @Test
        @DisplayName("가려도 상태는 그대로 보인다 — 답을 받았는지는 숨길 것이 아니다")
        void maskedStillShowsStatus() {
            when(loadInquiryPort.findByProductId(100L, InquiryUseCase.MAX_PRODUCT_LIST_SIZE))
                    .thenReturn(List.of(stored(2L, OWNER, true, List.of(answer(10L)))));

            assertThat(service().listForProduct(100L, STRANGER, false).get(0).status())
                    .isEqualTo(InquiryStatus.ANSWERED);
        }

        @Test
        @DisplayName("작성자 본인에게는 원문 그대로")
        void ownerSeesOriginal() {
            Inquiry secret = stored(2L, OWNER, true, List.of());
            when(loadInquiryPort.findByProductId(100L, InquiryUseCase.MAX_PRODUCT_LIST_SIZE))
                    .thenReturn(List.of(secret));

            assertThat(service().listForProduct(100L, OWNER, false)).containsExactly(secret);
        }

        @Test
        @DisplayName("관리자에게도 원문 그대로")
        void adminSeesOriginal() {
            Inquiry secret = stored(2L, OWNER, true, List.of());
            when(loadInquiryPort.findByProductId(100L, InquiryUseCase.MAX_PRODUCT_LIST_SIZE))
                    .thenReturn(List.of(secret));

            assertThat(service().listForProduct(100L, ADMIN, true)).containsExactly(secret);
        }
    }

    @Nested
    @DisplayName("수정·철회")
    class Modify {

        @Test
        @DisplayName("남의 문의는 못 고친다")
        void strangerCannotEdit() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));

            assertThatThrownBy(() -> service().edit(1L, STRANGER, "제목", "본문", false))
                    .isInstanceOf(AccessDeniedException.class);
            verify(saveInquiryPort, never()).update(any());
        }

        @Test
        @DisplayName("공개된 상품 문의라도 남이 고칠 수는 없다 — 읽기와 쓰기의 기준이 다르다")
        void publiclyReadableIsStillNotEditable() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));

            assertThatThrownBy(() -> service().edit(1L, STRANGER, "제목", "본문", false))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("답변이 달린 뒤에는 수정이 막힌다")
        void notEditableAfterAnswer() {
            when(loadInquiryPort.findById(1L))
                    .thenReturn(Optional.of(stored(1L, OWNER, false, List.of(answer(10L)))));

            assertThatThrownBy(() -> service().edit(1L, OWNER, "바꾼 제목", "바꾼 본문", false))
                    .isInstanceOf(InquiryAlreadyAnsweredException.class);
            verify(saveInquiryPort, never()).update(any());
        }

        @Test
        @DisplayName("본인이 답변 전에 고치면 그대로 저장된다")
        void ownerEdits() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));
            when(saveInquiryPort.update(any())).thenAnswer(i -> i.getArgument(0));

            Inquiry edited = service().edit(1L, OWNER, "새 제목", "새 본문", true);

            assertThat(edited.subject()).isEqualTo("새 제목");
            assertThat(edited.secret()).isTrue();
            assertThat(edited.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("답변이 달린 뒤에는 철회도 막힌다")
        void notWithdrawableAfterAnswer() {
            when(loadInquiryPort.findById(1L))
                    .thenReturn(Optional.of(stored(1L, OWNER, false, List.of(answer(10L)))));

            assertThatThrownBy(() -> service().withdraw(1L, OWNER))
                    .isInstanceOf(InquiryAlreadyAnsweredException.class);
            verify(saveInquiryPort, never()).delete(anyLong());
        }

        @Test
        @DisplayName("남의 문의는 못 지운다")
        void strangerCannotWithdraw() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));

            assertThatThrownBy(() -> service().withdraw(1L, STRANGER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(saveInquiryPort, never()).delete(anyLong());
        }

        @Test
        @DisplayName("본인이 답변 전에 철회하면 지워진다")
        void ownerWithdraws() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));

            service().withdraw(1L, OWNER);

            verify(saveInquiryPort).delete(1L);
        }
    }

    @Nested
    @DisplayName("답변")
    class Answering {

        @Test
        @DisplayName("답변을 달면 그 순간 상태가 완료가 된다")
        void answerFlipsStatus() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));
            when(saveInquiryPort.addAnswer(eq(1L), any())).thenReturn(answer(10L));

            Inquiry answered = service().answer(1L, ADMIN, "정사이즈입니다.");

            assertThat(answered.status()).isEqualTo(InquiryStatus.ANSWERED);
            assertThat(answered.answers()).extracting(InquiryAnswer::id).containsExactly(10L);
            verify(notifyInquiryPort).notifyAnswered(answered);
        }

        @Test
        @DisplayName("답변 시각도 주입된 Clock 이 정한다")
        void answerUsesClock() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));
            when(saveInquiryPort.addAnswer(eq(1L), any())).thenReturn(answer(10L));

            service().answer(1L, ADMIN, "정사이즈입니다.");

            ArgumentCaptor<InquiryAnswer> captor = ArgumentCaptor.forClass(InquiryAnswer.class);
            verify(saveInquiryPort).addAnswer(eq(1L), captor.capture());
            assertThat(captor.getValue().answeredAt()).isEqualTo(NOW);
            assertThat(captor.getValue().id()).isNull();
        }

        @Test
        @DisplayName("없는 문의에는 답변을 달 수 없다")
        void cannotAnswerMissing() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().answer(1L, ADMIN, "답변"))
                    .isInstanceOf(InquiryNotFoundException.class);
            verify(saveInquiryPort, never()).addAnswer(anyLong(), any());
        }

        @Test
        @DisplayName("다른 문의의 답변 번호로는 못 지운다 — 레거시는 번호 하나만 보고 지웠다")
        void deleteAnswerChecksParent() {
            when(loadInquiryPort.findById(1L))
                    .thenReturn(Optional.of(stored(1L, OWNER, false, List.of(answer(10L)))));
            when(saveInquiryPort.deleteAnswer(1L, 777L)).thenReturn(false);

            assertThatThrownBy(() -> service().deleteAnswer(1L, 777L))
                    .isInstanceOf(InquiryAnswerNotFoundException.class);
        }

        @Test
        @DisplayName("답변을 지우면 상태가 다시 대기로 돌아온다")
        void deleteAnswerReturnsToWaiting() {
            when(loadInquiryPort.findById(1L))
                    .thenReturn(Optional.of(stored(1L, OWNER, false, List.of(answer(10L)))))
                    .thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));
            when(saveInquiryPort.deleteAnswer(1L, 10L)).thenReturn(true);

            Inquiry reloaded = service().deleteAnswer(1L, 10L);

            assertThat(reloaded.status()).isEqualTo(InquiryStatus.WAITING);
            assertThat(reloaded.answers()).isEmpty();
        }

        @Test
        @DisplayName("답변 대기 목록은 한도를 넘겨 읽지 않는다")
        void waitingIsBounded() {
            when(loadInquiryPort.findWaiting(InquiryUseCase.MAX_LIST_SIZE)).thenReturn(List.of());

            assertThatCode(() -> service().listWaiting()).doesNotThrowAnyException();
            verify(loadInquiryPort).findWaiting(InquiryUseCase.MAX_LIST_SIZE);
        }
    }

    @Nested
    @DisplayName("식별자 유효성")
    class Identifiers {

        @Test
        @DisplayName("inquiryId 없이 상세를 부르면 포트까지 가지 않는다")
        void nullInquiryIdRejected() {
            assertThatThrownBy(() -> service().get(null, OWNER, false))
                    .isInstanceOf(InquiryInvariantViolationException.class);
            verify(loadInquiryPort, never()).findById(any());
        }

        @Test
        @DisplayName("productId 없이 상품 목록을 부르면 포트까지 가지 않는다")
        void nullProductIdRejected() {
            assertThatThrownBy(() -> service().listForProduct(null, OWNER, false))
                    .isInstanceOf(InquiryInvariantViolationException.class);
            verify(loadInquiryPort, never()).findByProductId(anyLong(), anyInt());
        }

        @Test
        @DisplayName("답변자가 없으면 답변이 저장되지 않는다")
        void nullAnswererRejected() {
            when(loadInquiryPort.findById(1L)).thenReturn(Optional.of(stored(1L, OWNER, false, List.of())));

            assertThatThrownBy(() -> service().answer(1L, null, "답변"))
                    .isInstanceOf(InquiryInvariantViolationException.class);
            verify(saveInquiryPort, never()).addAnswer(anyLong(), any());
        }
    }
}
