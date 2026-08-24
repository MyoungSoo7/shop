package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.out.LoadBoardAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.StoreAttachmentPort;
import github.lms.lemuel.operation.board.application.port.out.StoreAttachmentPort.StoredFile;
import github.lms.lemuel.operation.board.config.AttachmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 고아 청소 테스트.
 *
 * <p>이 클래스가 지키는 것은 <b>"지우지 말아야 할 것을 지우지 않는다"</b>이다. 청소기가 과하면
 * 사용자가 방금 올린 첨부가 사라지고, 그 사고는 로그를 뒤지기 전까지 원인을 알 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class OrphanAttachmentCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Mock
    private LoadBoardAttachmentPort loadBoardAttachmentPort;
    @Mock
    private StoreAttachmentPort storeAttachmentPort;

    private OrphanAttachmentCleanupService service;

    @BeforeEach
    void setUp() {
        service = new OrphanAttachmentCleanupService(loadBoardAttachmentPort, storeAttachmentPort,
                new AttachmentProperties("./data", true, null, 24),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StoredFile file(String path, long hoursAgo) {
        return new StoredFile(path, NOW.minusSeconds(hoursAgo * 3600));
    }

    @Test
    @DisplayName("DB 가 참조하는 파일은 아무리 오래돼도 지우지 않는다")
    void keepsReferencedFiles() {
        when(storeAttachmentPort.listAll()).thenReturn(List.of(file("board-1/post-1/a.jpg", 999)));
        when(loadBoardAttachmentPort.findAllReferencedPaths()).thenReturn(Set.of("board-1/post-1/a.jpg"));

        assertThat(service.cleanupOrphans()).isEqualTo(
                new github.lms.lemuel.operation.board.application.port.in.CleanupOrphanAttachmentUseCase
                        .CleanupResult(1, 0));
        verify(storeAttachmentPort, never()).delete(anyString());
    }

    @Test
    @DisplayName("축소본도 참조로 친다 — 빠뜨리면 살아 있는 썸네일을 지운다")
    void thumbnailPathCountsAsReference() {
        when(storeAttachmentPort.listAll()).thenReturn(List.of(
                file("board-1/post-1/a.jpg", 100),
                file("board-1/post-1/a-thumb.png", 100)));
        when(loadBoardAttachmentPort.findAllReferencedPaths())
                .thenReturn(Set.of("board-1/post-1/a.jpg", "board-1/post-1/a-thumb.png"));

        assertThat(service.cleanupOrphans().deleted()).isZero();
    }

    @Test
    @DisplayName("유예 기간 안의 파일은 참조가 없어도 두 번 본다 — 업로드 중일 수 있다")
    void keepsRecentFiles() {
        when(storeAttachmentPort.listAll()).thenReturn(List.of(file("board-1/post-1/new.jpg", 1)));
        when(loadBoardAttachmentPort.findAllReferencedPaths()).thenReturn(Set.of());

        assertThat(service.cleanupOrphans().deleted()).isZero();
        verify(storeAttachmentPort, never()).delete(anyString());
    }

    @Test
    @DisplayName("유예 경계 — 24시간 정확히는 남기고 그보다 오래된 것만 지운다")
    void graceBoundary() {
        when(storeAttachmentPort.listAll()).thenReturn(List.of(
                file("board-1/post-1/exactly.jpg", 24),
                file("board-1/post-1/older.jpg", 25)));
        when(loadBoardAttachmentPort.findAllReferencedPaths()).thenReturn(Set.of());

        assertThat(service.cleanupOrphans().deleted()).isEqualTo(1);
        verify(storeAttachmentPort).delete("board-1/post-1/older.jpg");
        verify(storeAttachmentPort, never()).delete("board-1/post-1/exactly.jpg");
    }

    @Test
    @DisplayName("참조 없고 오래된 파일만 지운다")
    void deletesOldOrphans() {
        when(storeAttachmentPort.listAll()).thenReturn(List.of(
                file("board-1/post-1/keep.jpg", 100),
                file("board-1/post-1/orphan.jpg", 100),
                file("board-2/post-9/orphan2.png", 48)));
        when(loadBoardAttachmentPort.findAllReferencedPaths()).thenReturn(Set.of("board-1/post-1/keep.jpg"));

        assertThat(service.cleanupOrphans()).isEqualTo(
                new github.lms.lemuel.operation.board.application.port.in.CleanupOrphanAttachmentUseCase
                        .CleanupResult(3, 2));
        verify(storeAttachmentPort).delete("board-1/post-1/orphan.jpg");
        verify(storeAttachmentPort).delete("board-2/post-9/orphan2.png");
    }

    @Test
    @DisplayName("저장소가 비면 DB 를 읽지도 않는다")
    void emptyStorageSkipsQuery() {
        when(storeAttachmentPort.listAll()).thenReturn(List.of());

        assertThat(service.cleanupOrphans().scanned()).isZero();
        verifyNoInteractions(loadBoardAttachmentPort);
    }

    @Test
    @DisplayName("유예 기간을 0 이하로 설정해도 기본값으로 바닥을 깐다 — 업로드 중 파일을 지우지 않게")
    void graceHasFloor() {
        AttachmentProperties reckless = new AttachmentProperties("./data", true, null, 0);

        assertThat(reckless.cleanupGraceHours()).isEqualTo(24);
    }
}
