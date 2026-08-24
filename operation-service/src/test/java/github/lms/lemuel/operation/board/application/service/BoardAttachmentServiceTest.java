package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.out.DetectFileTypePort;
import github.lms.lemuel.operation.board.application.port.out.GenerateThumbnailPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardPostPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.StoreAttachmentPort;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardAttachment;
import github.lms.lemuel.operation.board.domain.BoardAttachmentKind;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardAuthor;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.DetectedFileType;
import github.lms.lemuel.operation.board.domain.exception.BoardAccessDeniedException;
import github.lms.lemuel.operation.board.domain.exception.BoardAttachmentNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.operation.board.domain.exception.BoardPostNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardAttachmentServiceTest {

    private static final Instant FIXED = Instant.parse("2026-08-15T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);

    private static final BoardActor AUTHOR = BoardActor.of(10L, "USER");
    private static final BoardActor STRANGER = BoardActor.of(11L, "USER");
    private static final BoardAuthor AUTHOR_NAME = new BoardAuthor(10L, "au***");
    private static final DetectedFileType JPEG = DetectedFileType.of("jpg", "image/jpeg", true, "jpeg");
    private static final byte[] CONTENT = new byte[]{1, 2, 3, 4};

    @Mock
    private LoadBoardDefinitionPort loadBoardDefinitionPort;
    @Mock
    private LoadBoardPostPort loadBoardPostPort;
    @Mock
    private LoadBoardAttachmentPort loadBoardAttachmentPort;
    @Mock
    private SaveBoardAttachmentPort saveBoardAttachmentPort;
    @Mock
    private StoreAttachmentPort storeAttachmentPort;
    @Mock
    private DetectFileTypePort detectFileTypePort;
    @Mock
    private GenerateThumbnailPort generateThumbnailPort;

    private BoardAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new BoardAttachmentService(loadBoardDefinitionPort, loadBoardPostPort,
                loadBoardAttachmentPort, saveBoardAttachmentPort, storeAttachmentPort,
                detectFileTypePort, generateThumbnailPort, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static BoardDefinition definition(int maxCount) {
        return BoardDefinition.rehydrate(1L, "gallery", "갤러리", null, BoardSkin.GALLERY,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.rehydrate(true, maxCount, 100, List.of("jpg", "png")),
                BoardAccessPolicy.rehydrate(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                true, NOW, NOW);
    }

    private static BoardPost post() {
        return BoardPost.rehydrate(5L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                AUTHOR_NAME, false, false, BoardPostStatus.PUBLISHED, 0L, NOW, NOW);
    }

    private static BoardAttachment attachment(Long boardId) {
        return attachment(boardId, "board-1/post-5/thumb.png");
    }

    private static BoardAttachment attachment(Long boardId, String thumbnailPath) {
        return BoardAttachment.rehydrate(9L, 5L, boardId, BoardAttachmentKind.IMAGE, "photo.jpg",
                "uuid.jpg", "board-1/post-5/uuid.jpg", thumbnailPath, "image/jpeg", 4, 0, NOW);
    }

    @Test
    @DisplayName("업로드는 판정 → 검증 → 저장 → 행 기록 순으로 흐른다")
    void uploadHappyPath() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);
        when(storeAttachmentPort.store(1L, 5L, "jpg", CONTENT))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.jpg", "board-1/post-5/uuid.jpg"));
        when(saveBoardAttachmentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardAttachment saved = service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT);

        assertThat(saved.getKind()).isEqualTo(BoardAttachmentKind.IMAGE);
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getOriginalName()).isEqualTo("photo.jpg");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("검증에 걸리면 디스크에 닿지 않는다 — 거절된 파일이 남지 않게")
    void rejectedUploadNeverTouchesDisk() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        // 확장자만 바꿔 올린 파일
        when(detectFileTypePort.detect(CONTENT))
                .thenReturn(DetectedFileType.of("pdf", "application/pdf", false));

        assertThatThrownBy(() -> service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT))
                .isInstanceOf(BoardInvariantViolationException.class);

        verify(storeAttachmentPort, never()).store(anyLong(), anyLong(), anyString(), any());
        verify(saveBoardAttachmentPort, never()).save(any());
    }

    @Test
    @DisplayName("개수 한도를 넘으면 거절한다 — 이미 붙은 수는 저장소가 알려 준다")
    void countLimit() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(2)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(2);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);

        assertThatThrownBy(() -> service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("최대 2개");

        verify(storeAttachmentPort, never()).store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("남의 글에는 첨부할 수 없다 — 첨부 권한은 글 수정 권한을 따른다")
    void strangerCannotAttach() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);

        assertThatThrownBy(() -> service.upload("gallery", 5L, STRANGER, "photo.jpg", CONTENT))
                .isInstanceOf(BoardAccessDeniedException.class);
    }

    @Test
    @DisplayName("행 기록이 실패하면 방금 쓴 파일을 되돌린다 — 파일시스템은 트랜잭션 밖이다")
    void compensatesFileOnDbFailure() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);
        when(storeAttachmentPort.store(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.jpg", "board-1/post-5/uuid.jpg"));
        when(saveBoardAttachmentPort.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT))
                .isInstanceOf(IllegalStateException.class);

        verify(storeAttachmentPort).delete("board-1/post-5/uuid.jpg");
    }

    @Test
    @DisplayName("볼 수 없는 글의 첨부는 404 — 첨부 URL 로 비밀글이 새지 않게")
    void downloadOfInvisiblePost() {
        BoardPost secret = BoardPost.rehydrate(5L, 1L, null, "제목", "본문", BoardContentFormat.TEXT,
                AUTHOR_NAME, false, true, BoardPostStatus.PUBLISHED, 0L, NOW, NOW);
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(secret));

        assertThatThrownBy(() -> service.download("gallery", 9L, STRANGER))
                .isInstanceOf(BoardPostNotFoundException.class);
        verify(storeAttachmentPort, never()).read(anyString());
    }

    @Test
    @DisplayName("다른 게시판의 첨부 식별자는 404")
    void attachmentFromAnotherBoard() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(2L)));

        assertThatThrownBy(() -> service.download("gallery", 9L, AUTHOR))
                .isInstanceOf(BoardAttachmentNotFoundException.class);
    }

    @Test
    @DisplayName("다운로드는 저장 경로로 바이트를 읽어 판정된 메타와 함께 돌려준다")
    void download() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(storeAttachmentPort.read("board-1/post-5/uuid.jpg")).thenReturn(CONTENT);

        var download = service.download("gallery", 9L, BoardActor.anonymous());

        assertThat(download.content()).isEqualTo(CONTENT);
        assertThat(download.attachment().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("삭제는 행을 먼저 지우고 파일을 지운다 — 참조가 남는 것보다 파일이 남는 편이 낫다")
    void delete() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));

        service.delete("gallery", 9L, AUTHOR);

        verify(saveBoardAttachmentPort).delete(9L);
        verify(storeAttachmentPort).delete("board-1/post-5/uuid.jpg");
    }

    @Test
    @DisplayName("남의 글 첨부는 지울 수 없다")
    void strangerCannotDelete() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));

        assertThatThrownBy(() -> service.delete("gallery", 9L, STRANGER))
                .isInstanceOf(BoardAccessDeniedException.class);
        verify(saveBoardAttachmentPort, never()).delete(anyLong());
    }

    @Test
    @DisplayName("목록은 글 가시성을 먼저 태운다")
    void listByPost() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.findByPostId(5L)).thenReturn(List.of(attachment(1L)));

        assertThat(service.listByPost("gallery", 5L, BoardActor.anonymous())).hasSize(1);
        verify(loadBoardAttachmentPort).findByPostId(eq(5L));
    }
    @Test
    @DisplayName("이미지는 축소본을 함께 저장한다 — 목록이 원본을 내려받지 않게")
    void storesThumbnailForImage() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);
        when(generateThumbnailPort.generate(eq(CONTENT), eq("jpg"), anyInt()))
                .thenReturn(Optional.of(new GenerateThumbnailPort.Thumbnail(new byte[]{9}, "png")));
        when(storeAttachmentPort.store(1L, 5L, "jpg", CONTENT))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.jpg", "board-1/post-5/uuid.jpg"));
        when(storeAttachmentPort.store(eq(1L), eq(5L), eq("png"), any()))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("thumb.png", "board-1/post-5/thumb.png"));
        when(saveBoardAttachmentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardAttachment saved = service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT);

        assertThat(saved.hasThumbnail()).isTrue();
        assertThat(saved.displayPath()).isEqualTo("board-1/post-5/thumb.png");
    }

    @Test
    @DisplayName("축소본을 못 만들어도 업로드는 성공한다 — 부가 기능이 본 기능을 죽이지 않는다")
    void uploadSucceedsWithoutThumbnail() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT)).thenReturn(JPEG);
        // WEBP·손상 이미지처럼 리더가 없는 경우
        when(generateThumbnailPort.generate(any(), anyString(), anyInt())).thenReturn(Optional.empty());
        when(storeAttachmentPort.store(1L, 5L, "jpg", CONTENT))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.jpg", "board-1/post-5/uuid.jpg"));
        when(saveBoardAttachmentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardAttachment saved = service.upload("gallery", 5L, AUTHOR, "photo.jpg", CONTENT);

        assertThat(saved.hasThumbnail()).isFalse();
        assertThat(saved.displayPath()).isEqualTo("board-1/post-5/uuid.jpg");
        verify(storeAttachmentPort, org.mockito.Mockito.times(1))
                .store(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("이미지가 아니면 축소본 생성기를 부르지 않는다")
    void skipsThumbnailForNonImage() {
        // 이 게시판만 pdf 를 허용한다 — 확장자 정책에 걸려 검증 단계에서 끝나면 검사하려는 것을 못 본다.
        BoardDefinition withPdf = BoardDefinition.rehydrate(1L, "gallery", "갤러리", null, BoardSkin.GALLERY,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.rehydrate(true, 3, 100, List.of("jpg", "png", "pdf")),
                BoardAccessPolicy.rehydrate(List.of(), List.of("USER"), List.of("USER"), List.of("ADMIN")),
                true, NOW, NOW);
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(withPdf));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.countByPostId(5L)).thenReturn(0);
        when(detectFileTypePort.detect(CONTENT))
                .thenReturn(DetectedFileType.of("pdf", "application/pdf", false));
        when(storeAttachmentPort.store(1L, 5L, "pdf", CONTENT))
                .thenReturn(new StoreAttachmentPort.StoredAttachment("uuid.pdf", "board-1/post-5/uuid.pdf"));
        when(saveBoardAttachmentPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.upload("gallery", 5L, AUTHOR, "안내문.pdf", CONTENT);

        verify(generateThumbnailPort, never()).generate(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("삭제는 원본과 축소본을 함께 지운다")
    void deleteRemovesBothFiles() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));

        service.delete("gallery", 9L, AUTHOR);

        verify(storeAttachmentPort).delete("board-1/post-5/uuid.jpg");
        verify(storeAttachmentPort).delete("board-1/post-5/thumb.png");
    }

    @Test
    @DisplayName("축소본 다운로드는 축소본을, 없으면 원본을 읽는다 — 화면이 분기하지 않게")
    void downloadThumbnailFallsBackToOriginal() {
        when(loadBoardDefinitionPort.findByKey("gallery")).thenReturn(Optional.of(definition(3)));
        when(loadBoardPostPort.findById(5L)).thenReturn(Optional.of(post()));
        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L)));
        when(storeAttachmentPort.read("board-1/post-5/thumb.png")).thenReturn(CONTENT);

        assertThat(service.downloadThumbnail("gallery", 9L, BoardActor.anonymous()).content())
                .isEqualTo(CONTENT);

        when(loadBoardAttachmentPort.findById(9L)).thenReturn(Optional.of(attachment(1L, null)));
        when(storeAttachmentPort.read("board-1/post-5/uuid.jpg")).thenReturn(CONTENT);

        assertThat(service.downloadThumbnail("gallery", 9L, BoardActor.anonymous()).content())
                .isEqualTo(CONTENT);
    }
}
