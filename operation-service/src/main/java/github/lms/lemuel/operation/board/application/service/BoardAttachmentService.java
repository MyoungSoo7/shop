package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.BoardAttachmentUseCase;
import github.lms.lemuel.operation.board.application.port.out.DetectFileTypePort;
import github.lms.lemuel.operation.board.application.port.out.GenerateThumbnailPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.StoreAttachmentPort;
import github.lms.lemuel.operation.board.domain.AttachmentUpload;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardAttachmentKind;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.exception.BoardAttachmentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 첨부 응용 서비스.
 *
 * <p><b>순서가 곧 안전이다</b>: 판정 → 검증 → 저장 → 행 기록. 검증을 저장 뒤로 미루면 거절당한
 * 파일이 디스크에 남고, 그 정리를 잊는 순간 아무도 참조하지 않는 바이너리가 쌓인다.
 *
 * <p>DB 기록이 실패하면 <b>방금 쓴 파일을 되돌린다</b>. 파일시스템은 트랜잭션에 참여하지 않으므로
 * 롤백이 자동으로 오지 않는다 — 이 보상 삭제가 없으면 실패할 때마다 고아 파일이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardAttachmentService implements BoardAttachmentUseCase {

    /** 목록 썸네일의 긴 변. 그리드 한 칸이 커야 400px 남짓이라 그 위로는 화면에서 차이가 없다. */
    private static final int THUMBNAIL_MAX_EDGE = 400;

    private final LoadBoardDefinitionPort loadBoardDefinitionPort;
    private final LoadBoardPostPort loadBoardPostPort;
    private final LoadBoardAttachmentPort loadBoardAttachmentPort;
    private final SaveBoardAttachmentPort saveBoardAttachmentPort;
    private final StoreAttachmentPort storeAttachmentPort;
    private final DetectFileTypePort detectFileTypePort;
    private final GenerateThumbnailPort generateThumbnailPort;
    private final Clock clock;

    @Override
    public List<BoardAttachment> listByPost(String boardKey, Long postId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = visiblePost(definition, postId, actor);
        return loadBoardAttachmentPort.findByPostId(post.getId());
    }

    @Override
    public Map<Long, BoardAttachment> firstImageByPost(String boardKey, List<Long> postIds, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        // 글 목록 자체가 이미 가시성으로 걸러진 결과라 여기서 다시 글별 판정을 하지 않는다.
        // 대신 게시판 소속만 대조한다 — 다른 게시판의 글 식별자가 섞여 들어와도 새지 않게.
        return loadBoardAttachmentPort.findFirstImageByPostIds(postIds).entrySet().stream()
                .filter(entry -> definition.getId().equals(entry.getValue().getBoardId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    @Transactional
    public BoardAttachment upload(String boardKey, Long postId, BoardActor actor,
                                  String originalName, byte[] content) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardPost post = visiblePost(definition, postId, actor);

        // ① 서버가 바이트를 보고 실제 형식을 정한다 — 요청이 주장한 Content-Type 은 쓰지 않는다.
        AttachmentUpload upload = new AttachmentUpload(originalName, content.length,
                detectFileTypePort.detect(content));
        int existing = loadBoardAttachmentPort.countByPostId(post.getId());

        // ② 권한·정책·형식을 전부 통과해야 디스크에 닿는다.
        post.assertCanAttach(actor, definition, upload, existing);

        StoreAttachmentPort.StoredAttachment stored = storeAttachmentPort.store(
                definition.getId(), post.getId(), upload.detectedType().extension(), content);
        String thumbnailPath = storeThumbnail(definition, post, upload, content);
        try {
            return saveBoardAttachmentPort.save(BoardAttachment.of(
                    post, upload, stored.storedName(), stored.storagePath(), thumbnailPath, existing, now()));
        } catch (RuntimeException e) {
            // 파일시스템은 트랜잭션 밖이다 — 손으로 되돌린다(축소본까지).
            storeAttachmentPort.delete(stored.storagePath());
            if (thumbnailPath != null) {
                storeAttachmentPort.delete(thumbnailPath);
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(String boardKey, Long attachmentId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardAttachment attachment = attachmentOf(definition, attachmentId);
        BoardPost post = visiblePost(definition, attachment.getPostId(), actor);

        attachment.assertRemovable(actor, definition, post);
        saveBoardAttachmentPort.delete(attachment.getId());
        // 행을 먼저 지운다 — 파일이 남는 것보다 참조가 남는 편이 나쁘다(404 대신 500).
        storeAttachmentPort.delete(attachment.getStoragePath());
        if (attachment.hasThumbnail()) {
            storeAttachmentPort.delete(attachment.getThumbnailPath());
        }
    }

    /**
     * 이미지면 축소본을 만들어 함께 저장한다. 만들지 못하면 {@code null} — 실패가 정상 경로다.
     *
     * <p>썸네일은 부가 기능이라 여기서 예외가 나 업로드 전체가 실패하면 안 된다. 그 판단은
     * {@code GenerateThumbnailPort} 구현이 {@code Optional} 로 표현한다.
     */
    private String storeThumbnail(BoardDefinition definition, BoardPost post,
                                  AttachmentUpload upload, byte[] content) {
        if (upload.kind() != BoardAttachmentKind.IMAGE) {
            return null;
        }
        return generateThumbnailPort.generate(content, upload.detectedType().extension(), THUMBNAIL_MAX_EDGE)
                .map(thumbnail -> storeAttachmentPort.store(definition.getId(), post.getId(),
                        thumbnail.extension(), thumbnail.content()).storagePath())
                .orElse(null);
    }

    @Override
    public AttachmentDownload download(String boardKey, Long attachmentId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardAttachment attachment = attachmentOf(definition, attachmentId);
        // 첨부는 글의 일부다 — 글이 안 보이면 첨부도 없는 것이다(첨부 URL 로 비밀글이 새지 않게).
        visiblePost(definition, attachment.getPostId(), actor);

        return new AttachmentDownload(attachment, storeAttachmentPort.read(attachment.getStoragePath()));
    }

    @Override
    public AttachmentDownload downloadThumbnail(String boardKey, Long attachmentId, BoardActor actor) {
        BoardDefinition definition = readableBoard(boardKey, actor);
        BoardAttachment attachment = attachmentOf(definition, attachmentId);
        visiblePost(definition, attachment.getPostId(), actor);

        return new AttachmentDownload(attachment, storeAttachmentPort.read(attachment.displayPath()));
    }

    private BoardDefinition readableBoard(String boardKey, BoardActor actor) {
        String normalized = boardKey == null ? null : boardKey.trim().toLowerCase(Locale.ROOT);
        BoardDefinition definition = loadBoardDefinitionPort.findByKey(normalized)
                .orElseThrow(() -> BoardNotFoundException.byKey(boardKey));
        if (!definition.isActive() || !definition.canRead(actor.role())) {
            throw BoardNotFoundException.byKey(boardKey);
        }
        return definition;
    }

    private BoardPost visiblePost(BoardDefinition definition, Long postId, BoardActor actor) {
        BoardPost post = loadBoardPostPort.findById(postId)
                .orElseThrow(() -> BoardPostNotFoundException.byId(postId));
        if (!definition.getId().equals(post.getBoardId()) || !post.isVisibleTo(actor, definition)) {
            throw BoardPostNotFoundException.byId(postId);
        }
        return post;
    }

    private BoardAttachment attachmentOf(BoardDefinition definition, Long attachmentId) {
        BoardAttachment attachment = loadBoardAttachmentPort.findById(attachmentId)
                .orElseThrow(() -> BoardAttachmentNotFoundException.byId(attachmentId));
        if (!definition.getId().equals(attachment.getBoardId())) {
            throw BoardAttachmentNotFoundException.byId(attachmentId);
        }
        return attachment;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
