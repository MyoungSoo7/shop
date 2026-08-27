package github.lms.lemuel.inquiry.application.service;

import github.lms.lemuel.inquiry.application.port.in.InquiryUseCase;
import github.lms.lemuel.inquiry.application.port.out.LoadInquiryPort;
import github.lms.lemuel.inquiry.application.port.out.NotifyInquiryPort;
import github.lms.lemuel.inquiry.application.port.out.SaveInquiryPort;
import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.inquiry.domain.exception.InquiryAnswerNotFoundException;
import github.lms.lemuel.inquiry.domain.exception.InquiryInvariantViolationException;
import github.lms.lemuel.inquiry.domain.exception.InquiryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 문의 운영.
 *
 * <p>여기서 지키는 것은 넷이다.
 *
 * <p><b>1. 등록의 성패와 알림의 성패를 섞지 않는다.</b> 레거시는 문의를 넣은 뒤 관리자 목록을 돌며
 * 알림톡을 보냈고, 그 루프가 같은 {@code try} 안에 있었다. 발송이 한 번 실패하면 사용자에게는
 * "등록 실패"({@code "8888"})가 나갔고 행은 이미 들어가 있었다. 여기서 알림은 <b>best-effort</b>
 * 이며 {@link NotifyInquiryPort} 구현이 예외를 밖으로 내지 않는다.
 *
 * <p><b>2. 남의 문의는 읽기 전에 막는다.</b> 레거시는 상세를 읽고 <b>결과를 확인하지 않은 채</b>
 * 필드를 꺼내(→ NPE → 500) 소유권 판단이 사실상 없었다. 여기서는 조회 직후에
 * {@link Inquiry#isReadableBy} 로 판정하고, 아니면 {@link AccessDeniedException} 이다.
 *
 * <p><b>3. 답변이 달린 뒤에는 못 고친다.</b> {@link Inquiry#requireEditable()}. 레거시
 * {@code updateOneToOneQna} 에는 이 검사가 없어, 답을 받은 뒤 질문을 바꾸면 서로 맞지 않는
 * 질문·답 한 쌍이 남았다.
 *
 * <p><b>4. 답변 상태는 저장하지 않는다.</b> 답변 유무에서 매번 계산하므로, 답변을 지우면 같은
 * 순간 목록도 "답변 대기"로 돌아온다.
 */
@Service
@Transactional
public class InquiryService implements InquiryUseCase {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    private final LoadInquiryPort loadInquiryPort;
    private final SaveInquiryPort saveInquiryPort;
    private final NotifyInquiryPort notifyInquiryPort;
    private final Clock clock;

    public InquiryService(LoadInquiryPort loadInquiryPort,
                          SaveInquiryPort saveInquiryPort,
                          NotifyInquiryPort notifyInquiryPort,
                          Clock clock) {
        this.loadInquiryPort = loadInquiryPort;
        this.saveInquiryPort = saveInquiryPort;
        this.notifyInquiryPort = notifyInquiryPort;
        this.clock = clock;
    }

    @Override
    public Inquiry ask(AskCommand command) {
        requireCommand(command);

        Inquiry saved = saveInquiryPort.save(Inquiry.ask(
                command.userId(),
                command.type(),
                command.productId(),
                command.orderId(),
                command.subject(),
                command.content(),
                command.secret(),
                LocalDateTime.now(clock)));

        // 알림은 여기서부터 별개다 — 실패해도 위 저장은 그대로 성공이다.
        quietly(() -> notifyInquiryPort.notifyAsked(saved));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inquiry> listMine(Long userId, InquiryType type) {
        requireUser(userId);
        return loadInquiryPort.findByUserId(userId, type, MAX_LIST_SIZE);
    }

    @Override
    @Transactional(readOnly = true)
    public Inquiry get(Long inquiryId, Long viewerId, boolean admin) {
        Inquiry inquiry = load(inquiryId);
        if (!inquiry.isReadableBy(viewerId, admin)) {
            throw new AccessDeniedException("본인 문의가 아닙니다. inquiryId=" + inquiryId);
        }
        return inquiry;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inquiry> listForProduct(Long productId, Long viewerId, boolean admin) {
        requireProduct(productId);
        return loadInquiryPort.findByProductId(productId, MAX_PRODUCT_LIST_SIZE).stream()
                .map(inquiry -> inquiry.isReadableBy(viewerId, admin) ? inquiry : mask(inquiry))
                .toList();
    }

    @Override
    public Inquiry edit(Long inquiryId, Long requesterId, String subject, String content, boolean secret) {
        Inquiry inquiry = requireOwned(load(inquiryId), requesterId);
        return saveInquiryPort.update(inquiry.edit(subject, content, secret));
    }

    @Override
    public void withdraw(Long inquiryId, Long requesterId) {
        Inquiry inquiry = requireOwned(load(inquiryId), requesterId);
        inquiry.requireEditable();
        saveInquiryPort.delete(inquiryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Inquiry> listWaiting() {
        return loadInquiryPort.findWaiting(MAX_LIST_SIZE);
    }

    @Override
    public Inquiry answer(Long inquiryId, Long answererId, String content) {
        Inquiry inquiry = load(inquiryId);
        if (answererId == null) {
            throw new InquiryInvariantViolationException("answererId 필수");
        }

        InquiryAnswer saved = saveInquiryPort.addAnswer(
                inquiryId, InquiryAnswer.of(answererId, content, LocalDateTime.now(clock)));
        Inquiry answered = inquiry.withAnswer(saved);

        quietly(() -> notifyInquiryPort.notifyAnswered(answered));
        return answered;
    }

    @Override
    public Inquiry deleteAnswer(Long inquiryId, Long answerId) {
        Inquiry inquiry = load(inquiryId);
        if (!saveInquiryPort.deleteAnswer(inquiryId, answerId)) {
            // 부모까지 대조해서 지운다. 레거시는 답변 번호 하나만 보고 지워, 다른 문의의 답변도
            // 지워졌다.
            throw new InquiryAnswerNotFoundException(inquiryId, answerId);
        }
        return load(inquiry.id());
    }

    /**
     * 볼 수 없는 문의를 목록에서 빼지 않고 <b>가린다.</b>
     *
     * <p>빼 버리면 작성자는 자기 질문이 등록됐는지 확인할 방법이 없고, 상품 문의 개수도 사람마다
     * 다르게 보인다. 제목은 {@link Inquiry#MASKED_SUBJECT}, 본문은 한 글자 마침표로 바꾼다 —
     * 도메인이 빈 본문을 허용하지 않기 때문이며, 이 자리에 무엇이 들어가든 화면은 비밀글 뱃지를
     * 보고 그리지 본문을 그리지 않는다.
     */
    private static Inquiry mask(Inquiry inquiry) {
        return new Inquiry(inquiry.id(), inquiry.userId(), inquiry.type(),
                inquiry.productId(), inquiry.orderId(),
                Inquiry.MASKED_SUBJECT, ".", true, inquiry.askedAt(),
                // 답변 본문도 함께 가린다. 질문을 가려 놓고 답변을 그대로 두면 아무 소용이 없다.
                inquiry.answers().stream()
                        .map(a -> new InquiryAnswer(a.id(), a.answeredBy(), ".", a.answeredAt()))
                        .toList());
    }

    /**
     * 알림을 부르되, 그 실패가 여기까지 올라오지 않게 한다.
     *
     * <p>{@link NotifyInquiryPort} 구현이 예외를 내지 않기로 되어 있지만 그것은 <b>약속</b>일 뿐이다.
     * 레거시가 바로 그 약속에 기대어 발송 루프를 등록과 같은 {@code try} 안에 두었고, 게이트웨이가
     * 한 번 흔들리자 이미 들어간 행을 두고 사용자에게 "등록 실패"가 나갔다. 새 구현이 언제 무엇을
     * 던지든 등록·답변의 성패는 여기서 갈라진다.
     */
    private static void quietly(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException e) {
            log.warn("문의 알림 발송에 실패했습니다. 등록·답변 자체는 정상입니다.", e);
        }
    }

    private Inquiry load(Long inquiryId) {
        if (inquiryId == null) {
            throw new InquiryInvariantViolationException("inquiryId 필수");
        }
        return loadInquiryPort.findById(inquiryId)
                .orElseThrow(() -> new InquiryNotFoundException(inquiryId));
    }

    private static Inquiry requireOwned(Inquiry inquiry, Long requesterId) {
        requireUser(requesterId);
        if (!inquiry.isOwnedBy(requesterId)) {
            throw new AccessDeniedException("본인 문의가 아닙니다. inquiryId=" + inquiry.id());
        }
        return inquiry;
    }

    private static void requireCommand(AskCommand command) {
        if (command == null) {
            throw new InquiryInvariantViolationException("문의 요청 필수");
        }
        requireUser(command.userId());
        if (command.type() == null) {
            throw new InquiryInvariantViolationException("문의 종류를 골라 주세요.");
        }
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new InquiryInvariantViolationException("userId 필수");
        }
    }

    private static void requireProduct(Long productId) {
        if (productId == null) {
            throw new InquiryInvariantViolationException("productId 필수");
        }
    }
}
