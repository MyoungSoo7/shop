package github.lms.lemuel.operation.board.adapter.out.persistence;

import github.lms.lemuel.operation.board.application.port.out.LoadBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardAttachmentPort;
import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardAttachmentKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BoardAttachmentPersistenceAdapter implements LoadBoardAttachmentPort, SaveBoardAttachmentPort {

    private final SpringDataBoardAttachmentRepository repository;

    @Override
    public Optional<BoardAttachment> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id).map(BoardAttachmentJpaEntity::toDomain);
    }

    @Override
    public List<BoardAttachment> findByPostId(Long postId) {
        return repository.findAllByPostIdOrderBySortOrderAscIdAsc(postId).stream()
                .map(BoardAttachmentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public int countByPostId(Long postId) {
        return repository.countByPostId(postId);
    }

    @Override
    public Map<Long, BoardAttachment> findFirstImageByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        // 정렬순으로 받아 글별 첫 장만 남긴다 — (existing, replacement) -> existing 가 그 "첫 장"을 지킨다.
        return repository
                .findAllByPostIdInAndKindOrderBySortOrderAscIdAsc(postIds, BoardAttachmentKind.IMAGE).stream()
                .map(BoardAttachmentJpaEntity::toDomain)
                .collect(Collectors.toMap(BoardAttachment::getPostId, attachment -> attachment,
                        (existing, replacement) -> existing));
    }

    @Override
    public Set<String> findAllReferencedPaths() {
        Set<String> referenced = new HashSet<>(repository.findAllStoragePaths());
        referenced.addAll(repository.findAllThumbnailPaths());
        return referenced;
    }

    @Override
    public BoardAttachment save(BoardAttachment attachment) {
        // 첨부는 수정되지 않는다 — 바꾸려면 지우고 다시 올린다. 그래서 upsert 분기가 없다.
        return repository.save(BoardAttachmentJpaEntity.from(attachment)).toDomain();
    }

    @Override
    public void delete(Long id) {
        repository.deleteById(id);
    }
}
