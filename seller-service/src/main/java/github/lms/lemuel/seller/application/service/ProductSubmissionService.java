package github.lms.lemuel.seller.application.service;

import github.lms.lemuel.seller.application.port.dto.SubmissionPage;
import github.lms.lemuel.seller.application.port.dto.SubmissionQuery;
import github.lms.lemuel.seller.application.port.dto.SubmissionView;
import github.lms.lemuel.seller.application.port.in.ManageProductSubmissionUseCase;
import github.lms.lemuel.seller.application.port.in.ViewProductSubmissionUseCase;
import github.lms.lemuel.seller.application.port.out.ProductSubmissionPort;
import github.lms.lemuel.seller.domain.ProductContent;
import github.lms.lemuel.seller.domain.ProductSubmission;
import github.lms.lemuel.seller.domain.SellerScope;
import github.lms.lemuel.seller.domain.SubmissionType;
import github.lms.lemuel.seller.domain.exception.SubmissionNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 셀러가 자기 상품을 올리는 경로 전부 — 작성·수정·제출·조회.
 *
 * <p>쓰기 세 개가 모두 첫 줄에서 {@code scope.requireSubmitPermission()} 을 통과하고, 읽기는
 * {@code requireSellerId()} 를 통과한다. 이 두 줄이 이 클래스의 인가 전부이며, 그 아래 어느
 * 코드도 요청에서 온 셀러 식별자를 보지 않는다.
 *
 * <p><b>상태 전이는 여기에 없다.</b> 전부 {@link ProductSubmission} 안에 있다. 이 서비스가 하는
 * 일은 "누구인지 확인하고, 애그리거트에 시키고, 저장한다" 세 가지뿐이다.
 */
@Service
@Transactional(readOnly = true)
public class ProductSubmissionService implements ManageProductSubmissionUseCase, ViewProductSubmissionUseCase {

    private final ProductSubmissionPort submissionPort;
    private final Clock clock;

    public ProductSubmissionService(ProductSubmissionPort submissionPort, Clock clock) {
        this.submissionPort = submissionPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubmissionView create(SellerScope scope, long userId, SubmissionType type,
                                 Long baseProductId, ProductContent content) {
        long sellerId = scope.requireSubmitPermission();
        // NEW 인데 대상 상품번호가 실려 오면 버린다. 조용히 들고 가면 그 값이 화면에 "수정 대상"
        // 으로 보이는데 실제로는 아무 데도 안 쓰인다 — 화면과 데이터가 다른 말을 하게 된다.
        Long base = type == SubmissionType.UPDATE ? baseProductId : null;
        ProductSubmission draft = ProductSubmission.draft(
                sellerId, scope.organizationId(), userId, type, base, content);
        return SubmissionView.of(submissionPort.save(draft));
    }

    @Override
    @Transactional
    public SubmissionView update(SellerScope scope, long submissionId, ProductContent content) {
        long sellerId = scope.requireSubmitPermission();
        ProductSubmission current = mineOrThrow(sellerId, submissionId);
        return SubmissionView.of(submissionPort.save(current.withContent(content)));
    }

    @Override
    @Transactional
    public SubmissionView submit(SellerScope scope, long submissionId) {
        long sellerId = scope.requireSubmitPermission();
        ProductSubmission current = mineOrThrow(sellerId, submissionId);
        return SubmissionView.of(submissionPort.save(current.submit(OffsetDateTime.now(clock))));
    }

    @Override
    public SubmissionPage mine(SellerScope scope, SubmissionQuery query) {
        long sellerId = scope.requireSellerId();
        SubmissionQuery q = query.normalized();

        long total = submissionPort.countBySeller(sellerId, q.status());
        List<ProductSubmission> rows = total == 0
                // 총건수가 0 이면 두 번째 쿼리는 반드시 빈 결과다. 안 쏘는 게 맞다.
                ? List.of()
                : submissionPort.findBySeller(sellerId, q.status(), q.size(), (long) q.page() * q.size());

        return page(rows, q.page(), q.size(), total);
    }

    @Override
    public Optional<SubmissionView> mine(SellerScope scope, long submissionId) {
        long sellerId = scope.requireSellerId();
        return submissionPort.load(submissionId, sellerId).map(SubmissionView::of);
    }

    @Override
    public SubmissionPage pending(SubmissionQuery query) {
        // 여기만 셀러 스코프가 없다. 대신 웹 계층에서 ROLE_ADMIN 으로 잠근다 — 그 잠금이
        // 유일한 방어이므로, 이 메서드를 다른 곳에서 호출하려면 같은 잠금을 다시 만들어야 한다.
        SubmissionQuery q = query.normalized();
        long total = submissionPort.countPending();
        List<ProductSubmission> rows = total == 0
                ? List.of()
                : submissionPort.findPending(q.size(), (long) q.page() * q.size());
        return page(rows, q.page(), q.size(), total);
    }

    private ProductSubmission mineOrThrow(long sellerId, long submissionId) {
        return submissionPort.load(submissionId, sellerId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
    }

    private SubmissionPage page(List<ProductSubmission> rows, int page, int size, long total) {
        int totalPages = (int) Math.ceil((double) total / size);
        return new SubmissionPage(rows.stream().map(SubmissionView::of).toList(), page, size, total, totalPages);
    }
}
