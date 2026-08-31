package github.lms.lemuel.seller.adapter.in.web;

import github.lms.lemuel.seller.application.port.dto.SubmissionPage;
import github.lms.lemuel.seller.application.port.dto.SubmissionQuery;
import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.application.port.in.ReviewProductSubmissionUseCase;
import github.lms.lemuel.seller.application.port.in.ViewProductSubmissionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영자 심사 — 대기열, 승인, 반려.
 *
 * <p><b>이 컨트롤러만 인가 근거가 다르다.</b> 나머지 셋은 "내 조직" 이라는 스코프로 대상을
 * 좁히지만, 운영자는 어느 셀러에도 속하지 않는다. 그래서 여기서는 스코프를 만들지 않고
 * 경로 자체를 {@code ROLE_ADMIN} 으로 막는다({@code /api/seller/admin/**}).
 *
 * <p>그 차이 때문에 이 클래스는 {@code CurrentSellerUser.requireUserId()} 를 <b>대상 결정이
 * 아니라 기록</b>에만 쓴다 — 누가 승인했는지를 신청서에 남기기 위해서다.
 *
 * <p>승인이 곧 카탈로그 등록이 아니라는 점은 {@link ReviewProductSubmissionUseCase} 에 적어 두었다.
 * 화면도 그렇게 그려야 한다 — 승인 직후의 "상품번호 대기" 를 완료로 표시하면, 등록이 실패한 건과
 * 몇 초 뒤 성공할 건이 구분되지 않는다.
 */
@RestController
@RequestMapping("/api/seller/admin/submissions")
@RequiredArgsConstructor
public class SellerReviewController {

    private final ViewProductSubmissionUseCase viewSubmission;
    private final ReviewProductSubmissionUseCase reviewSubmission;

    /** 심사 대기열 — 제출된 순서대로. 상태 필터는 서비스가 SUBMITTED 로 고정한다. */
    @GetMapping
    public SubmissionPage pending(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return viewSubmission.pending(new SubmissionQuery(null, page, size));
    }

    @PostMapping("/{submissionId}/approve")
    public SubmissionView approve(@PathVariable long submissionId) {
        return reviewSubmission.approve(submissionId, CurrentSellerUser.requireUserId());
    }

    /** 반려. 사유가 비어 있으면 400 이다 — 사유 없는 반려는 셀러에게 아무 정보도 주지 않는다. */
    @PostMapping("/{submissionId}/reject")
    public SubmissionView reject(@PathVariable long submissionId, @RequestBody RejectRequest request) {
        return reviewSubmission.reject(submissionId, CurrentSellerUser.requireUserId(), request.reason());
    }

    /** 반려 요청 본문. */
    public record RejectRequest(String reason) {
    }
}
