package github.lms.lemuel.inquiry.adapter.out.persistence;

import github.lms.lemuel.inquiry.application.port.out.LoadInquiryPort;
import github.lms.lemuel.inquiry.application.port.out.SaveInquiryPort;
import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.inquiry.domain.exception.InquiryNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 문의 영속성 어댑터 (JPA/PostgreSQL).
 *
 * <p>목록을 읽을 때 답변은 <b>부모 id 들을 모아 한 번에</b> 가져온다. 문의마다 답변을 부르면
 * 목록 한 장이 곧 N+1 이고, 레거시가 답변 상태를 칼럼으로 들고 있었던 이유도 그 비용을 피하려던
 * 것이었다. 상태를 저장하지 않으면서 N+1 도 피하려면 이 묶음 조회가 필요하다.
 */
@Component
public class InquiryPersistenceAdapter implements LoadInquiryPort, SaveInquiryPort {

    private final SpringDataInquiryRepository inquiryRepository;
    private final SpringDataInquiryAnswerRepository answerRepository;

    public InquiryPersistenceAdapter(SpringDataInquiryRepository inquiryRepository,
                                     SpringDataInquiryAnswerRepository answerRepository) {
        this.inquiryRepository = inquiryRepository;
        this.answerRepository = answerRepository;
    }

    @Override
    public Optional<Inquiry> findById(Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .map(entity -> toDomain(entity, answersOf(entity.getId())));
    }

    @Override
    public List<Inquiry> findByUserId(Long userId, InquiryType type, int limit) {
        Pageable page = PageRequest.ofSize(limit);
        List<InquiryJpaEntity> rows = type == null
                ? inquiryRepository.findByUserIdOrderByAskedAtDesc(userId, page)
                : inquiryRepository.findByUserIdAndTypeOrderByAskedAtDesc(userId, type, page);
        return hydrate(rows);
    }

    @Override
    public List<Inquiry> findByProductId(Long productId, int limit) {
        return hydrate(inquiryRepository.findByProductIdOrderByAskedAtDesc(productId, PageRequest.ofSize(limit)));
    }

    @Override
    public List<Inquiry> findWaiting(int limit) {
        // 답변이 없다고 판정된 것들이라 답변 조회가 필요 없다. 그래도 같은 경로로 채워, 조회와
        // 판정이 어긋날 여지를 남기지 않는다.
        return hydrate(inquiryRepository.findWaiting(PageRequest.ofSize(limit)));
    }

    @Override
    @Transactional
    public Inquiry save(Inquiry inquiry) {
        InquiryJpaEntity saved = inquiryRepository.save(new InquiryJpaEntity(
                inquiry.id(), inquiry.userId(), inquiry.type(),
                inquiry.productId(), inquiry.orderId(),
                inquiry.subject(), inquiry.content(), inquiry.secret(), inquiry.askedAt()));
        return toDomain(saved, List.of());
    }

    @Override
    @Transactional
    public Inquiry update(Inquiry inquiry) {
        InquiryJpaEntity entity = inquiryRepository.findById(inquiry.id())
                .orElseThrow(() -> new InquiryNotFoundException(inquiry.id()));
        entity.edit(inquiry.subject(), inquiry.content(), inquiry.secret());
        return toDomain(inquiryRepository.save(entity), answersOf(entity.getId()));
    }

    @Override
    @Transactional
    public void delete(Long inquiryId) {
        // 답변을 먼저 지운다. DB 의 ON DELETE CASCADE 가 정본이지만, 같은 트랜잭션 안의 영속성
        // 컨텍스트에는 그 삭제가 반영되지 않는다.
        answerRepository.deleteByInquiryId(inquiryId);
        inquiryRepository.deleteById(inquiryId);
    }

    @Override
    @Transactional
    public InquiryAnswer addAnswer(Long inquiryId, InquiryAnswer answer) {
        InquiryAnswerJpaEntity saved = answerRepository.save(new InquiryAnswerJpaEntity(
                answer.id(), inquiryId, answer.answeredBy(), answer.content(), answer.answeredAt()));
        return toDomain(saved);
    }

    @Override
    @Transactional
    public boolean deleteAnswer(Long inquiryId, Long answerId) {
        return answerRepository.deleteByIdAndInquiryId(answerId, inquiryId) > 0;
    }

    private List<InquiryAnswer> answersOf(Long inquiryId) {
        return answerRepository.findByInquiryIdOrderByAnsweredAtAsc(inquiryId).stream()
                .map(InquiryPersistenceAdapter::toDomain)
                .toList();
    }

    private List<Inquiry> hydrate(List<InquiryJpaEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(InquiryJpaEntity::getId).toList();
        Map<Long, List<InquiryAnswer>> answers =
                answerRepository.findByInquiryIdInOrderByAnsweredAtAsc(ids).stream()
                        .collect(Collectors.groupingBy(InquiryAnswerJpaEntity::getInquiryId,
                                Collectors.mapping(InquiryPersistenceAdapter::toDomain, Collectors.toList())));
        return rows.stream()
                .map(row -> toDomain(row, answers.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    private static Inquiry toDomain(InquiryJpaEntity e, List<InquiryAnswer> answers) {
        return new Inquiry(e.getId(), e.getUserId(), e.getType(), e.getProductId(), e.getOrderId(),
                e.getSubject(), e.getContent(), e.isSecret(), e.getAskedAt(), answers);
    }

    private static InquiryAnswer toDomain(InquiryAnswerJpaEntity e) {
        return new InquiryAnswer(e.getId(), e.getAnsweredBy(), e.getContent(), e.getAnsweredAt());
    }
}
