package github.lms.lemuel.bulkorder.application.port.in;

import github.lms.lemuel.bulkorder.domain.BulkOrderColumnSpec;
import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;

import java.util.List;

/**
 * 대량주문 초안 — 업로드·검증·확정·폐기.
 *
 * <p>레거시 커머스(ssgb2e-front {@code OrderMultiController}) 의 흐름을 그대로 옮겼다:
 * 업로드 → 검증(오류행 리포트) → 고쳐서 재검증 → 확정 전환 / 폐기.
 * 검증과 확정이 분리돼 있어야 뒷쪽 한 행 때문에 앞쪽 수백 건이 이미 실주문으로 나가는 사태가 없다.
 */
public interface BulkOrderUseCase {

    /** 파일을 행으로 쪼개 초안으로 저장하고 <b>곧바로 검증까지</b> 수행한다(업로드의 목적이 검증이다). */
    BulkOrderDraft uploadAndValidate(Long uploaderUserId, String fileName, List<List<String>> rows);

    /** 고친 값으로 다시 검증한다. 오류 행이 사라지면 확정이 열린다. */
    BulkOrderDraft revalidate(Long draftId, Long requesterUserId);

    /**
     * 실주문 전환. 전 행이 통과한 초안에서만 열린다.
     *
     * <p>행 하나가 실패해도 나머지는 계속 진행하고, 이미 주문이 나간 행은 재확정에서 건너뛴다 —
     * 확정은 재시도되는 작업이고, 재시도가 중복 주문이 되면 안 된다.
     */
    ConfirmResult confirm(Long draftId, Long requesterUserId);

    void discard(Long draftId, Long requesterUserId);

    BulkOrderDraft get(Long draftId, Long requesterUserId);

    List<BulkOrderDraft> listMine(Long requesterUserId);

    /** 업로드 양식(열 정의) — 화면이 헤더와 안내를 그릴 때 쓴다. */
    List<BulkOrderColumnSpec> columnSpecs();

    /**
     * @param created 이번 호출로 생성된 주문 수
     * @param failed  실패한 행 수
     * @param lines   행별 결과(운영자가 실패 행만 고칠 수 있도록)
     */
    record ConfirmResult(Long draftId, String status, int created, int failed, List<Line> lines) {
        public record Line(int rowNumber, Long orderId, String error) { }
    }
}
