package github.lms.lemuel.inquiry.application.port.out;

import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryType;

import java.util.List;
import java.util.Optional;

/** 문의 조회 아웃바운드 포트. 돌아오는 {@link Inquiry} 에는 답변까지 채워져 있다. */
public interface LoadInquiryPort {

    Optional<Inquiry> findById(Long inquiryId);

    /**
     * 한 사용자의 문의. 최신순.
     *
     * @param type  {@code null} 이면 종류를 가리지 않는다
     * @param limit 최대 건수
     */
    List<Inquiry> findByUserId(Long userId, InquiryType type, int limit);

    /** 한 상품에 달린 문의. 최신순. 비밀글도 포함해서 돌려주고, 가리는 것은 서비스가 한다. */
    List<Inquiry> findByProductId(Long productId, int limit);

    /**
     * 아직 답변이 없는 문의들. 오래된 순.
     *
     * <p>"답변 없음"을 저장된 칼럼이 아니라 <b>답변 유무</b>로 판정한다 — 그 이유는
     * {@link github.lms.lemuel.inquiry.domain.InquiryStatus} 에 적었다.
     */
    List<Inquiry> findWaiting(int limit);
}
