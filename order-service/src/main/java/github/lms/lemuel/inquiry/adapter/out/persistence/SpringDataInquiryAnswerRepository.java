package github.lms.lemuel.inquiry.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SpringDataInquiryAnswerRepository extends JpaRepository<InquiryAnswerJpaEntity, Long> {

    List<InquiryAnswerJpaEntity> findByInquiryIdOrderByAnsweredAtAsc(Long inquiryId);

    /** 목록 한 장의 답변을 한 번에 읽는다. 문의마다 부르면 그것이 곧 N+1 이다. */
    List<InquiryAnswerJpaEntity> findByInquiryIdInOrderByAnsweredAtAsc(Collection<Long> inquiryIds);

    /**
     * 부모까지 대조해서 지운다.
     *
     * <p>레거시의 답변 삭제는 조건이 답변 식별자 하나뿐이라, 다른 문의의 답변 번호를 넣으면
     * 그것이 지워졌다.
     *
     * @return 지워진 행 수 (0 이면 그 문의의 답변이 아니었다)
     */
    long deleteByIdAndInquiryId(Long id, Long inquiryId);

    /** 문의를 지울 때 함께 지운다. @return 지워진 답변 수 */
    long deleteByInquiryId(Long inquiryId);
}
