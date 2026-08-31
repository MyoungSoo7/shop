package github.lms.lemuel.seller.adapter.in.web;

import github.lms.lemuel.seller.application.port.dto.SubmissionPage;
import github.lms.lemuel.seller.application.port.dto.SubmissionQuery;
import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.application.port.in.ManageProductSubmissionUseCase;
import github.lms.lemuel.seller.application.port.in.ResolveSellerScopeUseCase;
import github.lms.lemuel.seller.application.port.in.ViewProductSubmissionUseCase;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.SubmissionStatus;
import github.lms.lemuel.seller.domain.SubmissionType;
import github.lms.lemuel.seller.domain.exception.SubmissionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 셀러가 자기 상품을 등록하는 화면의 뒷면 — 이 서비스의 존재 이유.
 *
 * <p>등록이 <b>두 걸음</b>인 것이 레퍼런스(ssgb2e-outbackoffice)와 가장 크게 다른 점이다.
 * 저쪽은 저장 버튼 하나가 곧 심사 대기였고, 그래서 쓰다 만 신청서가 큐에 섞였다. 여기서는
 * {@code POST /products}(작성) 와 {@code POST /products/{id}/submit}(제출) 이 갈려 있어
 * 큐에 있는 것은 전부 "봐 달라고 낸 것" 이다.
 *
 * <p>어느 메서드도 셀러 번호를 받지 않는다. 조회든 쓰기든 대상은 {@link SellerScope} 가 정한다.
 */
@RestController
@RequestMapping("/api/seller/products")
@RequiredArgsConstructor
public class SellerProductController {

    private final ResolveSellerScopeUseCase resolveScope;
    private final ManageProductSubmissionUseCase manageSubmission;
    private final ViewProductSubmissionUseCase viewSubmission;

    /** 내 신청서 목록. {@code status} 를 안 주면 전체 상태. */
    @GetMapping
    public SubmissionPage list(@RequestParam(required = false) SubmissionStatus status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return viewSubmission.mine(scope(), new SubmissionQuery(status, page, size));
    }

    /**
     * 단건. 남의 신청번호를 넣으면 <b>존재 여부도 드러나지 않고</b> 404 로 끝난다 —
     * 조회 자체가 내 셀러로 필터되기 때문이다({@link SubmissionNotFoundException} 참조).
     */
    @GetMapping("/{submissionId}")
    public SubmissionView one(@PathVariable long submissionId) {
        return viewSubmission.mine(scope(), submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
    }

    /** 작성(DRAFT). 아직 아무 데도 나가지 않는다. STAFF 도 여기까지는 할 수 있다. */
    @PostMapping
    public SubmissionView create(@RequestBody SubmissionRequest request) {
        return manageSubmission.create(scope(), CurrentSellerUser.requireUserId(),
                request.resolvedType(), request.baseProductId(), request.toContent());
    }

    /** 내용 수정. 작성 중(DRAFT)이거나 반려(REJECTED)된 건만 고칠 수 있다. */
    @PutMapping("/{submissionId}")
    public SubmissionView update(@PathVariable long submissionId, @RequestBody SubmissionRequest request) {
        return manageSubmission.update(scope(), submissionId, request.toContent());
    }

    /** 심사에 올린다. STAFF 는 여기서 거절된다 — 화면이 버튼을 감춰도 API 는 따로 막는다. */
    @PostMapping("/{submissionId}/submit")
    public SubmissionView submit(@PathVariable long submissionId) {
        return manageSubmission.submit(scope(), submissionId);
    }

    private SellerScope scope() {
        return resolveScope.resolve(CurrentSellerUser.requireUserId());
    }

    /**
     * 등록·수정 요청 본문.
     *
     * <p>박싱 타입인 것은 의도다. {@code int stock} 으로 두면 필드를 안 보낸 요청이 재고 0 으로
     * 조용히 저장되는데, "재고를 안 적었다" 와 "재고가 0 이다" 는 다른 말이다. 여기서 명시적으로
     * 기본값을 정하고, 그 기본값이 무엇인지 한 곳에만 적어 둔다.
     *
     * <p>값 검증은 하지 않는다 — {@link ProductContent} 가 하고, 그게 도메인의 일이다. 여기에
     * {@code @NotBlank} 를 같이 달면 규칙이 두 벌이 되고 언제나 한쪽이 먼저 낡는다.
     */
    public record SubmissionRequest(
            SubmissionType type,
            Long baseProductId,
            String name,
            String description,
            BigDecimal price,
            Integer stock,
            String category,
            String imageUrl,
            Boolean displayVisible) {

        SubmissionType resolvedType() {
            return type == null ? SubmissionType.NEW : type;
        }

        ProductContent toContent() {
            return new ProductContent(
                    name,
                    description,
                    price,
                    stock == null ? 0 : stock,
                    category,
                    imageUrl,
                    displayVisible == null || displayVisible);
        }
    }
}
