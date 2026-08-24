package github.lms.lemuel.bulkorder.application.port.out;

import github.lms.lemuel.bulkorder.domain.BulkOrderDraft;

import java.util.List;
import java.util.Optional;

public interface BulkOrderDraftPort {

    BulkOrderDraft save(BulkOrderDraft draft);

    Optional<BulkOrderDraft> findById(Long id);

    /** 내가 올린 초안 목록(최근 순). 남의 초안은 보이지 않는다. */
    List<BulkOrderDraft> findByUploader(Long uploaderUserId);
}
