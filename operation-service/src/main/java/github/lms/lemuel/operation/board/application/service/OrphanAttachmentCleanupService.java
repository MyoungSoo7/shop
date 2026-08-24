package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.CleanupOrphanAttachmentUseCase;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.StoreAttachmentPort;
import github.lms.lemuel.operation.board.config.AttachmentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 고아 첨부 청소.
 *
 * <p><b>유예 기간이 이 서비스의 안전장치다.</b> 업로드는 "파일 저장 → DB 기록" 순서라 그 사이에
 * 아주 짧게 <b>정상 파일도 고아처럼 보인다</b>. 유예 없이 지우면 방금 올린 첨부를 청소기가
 * 가져가고, 사용자는 이유를 알 수 없는 404 를 본다.
 *
 * <p>판단이 갈릴 때는 <b>남기는 쪽</b>으로 기운다 — 남은 파일은 디스크를 조금 먹는 문제지만,
 * 잘못 지운 파일은 되돌릴 수 없다.
 *
 * <p>다중 인스턴스 잠금(ShedLock)을 두지 않는다. 삭제가 멱등이고(이미 없으면 성공), 두 인스턴스가
 * 같은 고아를 지워도 결과가 같기 때문이다 — 잠금 테이블을 들이는 값이 이득보다 크다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanAttachmentCleanupService implements CleanupOrphanAttachmentUseCase {

    private final LoadBoardAttachmentPort loadBoardAttachmentPort;
    private final StoreAttachmentPort storeAttachmentPort;
    private final AttachmentProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public CleanupResult cleanupOrphans() {
        List<StoreAttachmentPort.StoredFile> files = storeAttachmentPort.listAll();
        if (files.isEmpty()) {
            return new CleanupResult(0, 0);
        }
        // 참조 목록을 먼저 읽는다. 순서가 반대면 그 사이에 올라온 파일이 "참조 없음"으로 보인다.
        Set<String> referenced = loadBoardAttachmentPort.findAllReferencedPaths();
        Instant cutoff = Instant.now(clock).minus(Duration.ofHours(properties.cleanupGraceHours()));

        int deleted = 0;
        for (StoreAttachmentPort.StoredFile file : files) {
            if (referenced.contains(file.storagePath())) {
                continue;
            }
            // 경계(정확히 유예 시간)는 남긴다 — 판단이 갈릴 때 남기는 쪽으로 기운다는 원칙 그대로다.
            // 남은 파일은 다음 바퀴에 다시 보지만, 잘못 지운 파일은 다음이 없다.
            if (!file.lastModified().isBefore(cutoff)) {
                continue;
            }
            log.info("고아 첨부 삭제: {} (최종 수정 {})", file.storagePath(), file.lastModified());
            storeAttachmentPort.delete(file.storagePath());
            deleted++;
        }
        return new CleanupResult(files.size(), deleted);
    }
}
