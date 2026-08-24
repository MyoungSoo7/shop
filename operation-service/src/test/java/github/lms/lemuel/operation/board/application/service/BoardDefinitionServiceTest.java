package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.AccessSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.AttachmentSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.ContentSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.CreateBoardCommand;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.UpdateBoardCommand;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.application.port.out.SaveBoardDefinitionPort;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.operation.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.operation.board.domain.exception.DuplicateBoardKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardDefinitionServiceTest {

    private static final Instant FIXED = Instant.parse("2026-08-15T00:00:00Z");

    @Mock
    private LoadBoardDefinitionPort loadPort;
    @Mock
    private SaveBoardDefinitionPort savePort;

    private BoardDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new BoardDefinitionService(loadPort, savePort, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static ContentSpec content(boolean comments) {
        return new ContentSpec(BoardContentFormat.TEXT, comments, false, null);
    }

    private static AccessSpec access() {
        return new AccessSpec(List.of(), List.of("ADMIN"), List.of("USER"), List.of("ADMIN"));
    }

    private static CreateBoardCommand createCommand(String key, AttachmentSpec attachment, BoardSkin skin) {
        return new CreateBoardCommand(key, "공지사항", "안내", skin, content(true), attachment, access());
    }

    private static BoardDefinition existing(String key, boolean active) {
        BoardDefinition definition = BoardDefinition.rehydrate(1L, key, "공지사항", null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(List.of(), List.of("ADMIN"), List.of("USER"), List.of("ADMIN")),
                active, OffsetDateTime.parse("2026-01-01T00:00Z"), OffsetDateTime.parse("2026-01-01T00:00Z"));
        return definition;
    }

    @Test
    @DisplayName("생성 시 키를 정규화해 중복을 검사하고 저장한다")
    void createNormalizesAndSaves() {
        when(loadPort.existsByKey("notice")).thenReturn(false);
        when(savePort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardDefinition created = service.create(
                createCommand("  NOTICE  ", new AttachmentSpec(false, 0, 0, List.of()), BoardSkin.LIST));

        assertThat(created.getBoardKey()).isEqualTo("notice");
        assertThat(created.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC));
        verify(loadPort).existsByKey("notice");
        verify(savePort).save(any(BoardDefinition.class));
    }

    @Test
    @DisplayName("키가 이미 있으면 409 로 이어질 예외를 던지고 저장하지 않는다")
    void createRejectsDuplicateKey() {
        when(loadPort.existsByKey("notice")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                createCommand("Notice", new AttachmentSpec(false, 0, 0, List.of()), BoardSkin.LIST)))
                .isInstanceOf(DuplicateBoardKeyException.class)
                .hasMessageContaining("notice");

        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("첨부가 꺼져 있으면 입력한 개수·크기를 버리고 정규형(0/0/빈집합)으로 접는다")
    void disabledAttachmentIsCanonicalized() {
        when(loadPort.existsByKey(anyString())).thenReturn(false);
        when(savePort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardDefinition created = service.create(
                createCommand("notice", new AttachmentSpec(false, 5, 2048, List.of("jpg")), BoardSkin.LIST));

        assertThat(created.getAttachmentPolicy().isEnabled()).isFalse();
        assertThat(created.getAttachmentPolicy().maxCount()).isZero();
        assertThat(created.getAttachmentPolicy().allowedExtensions()).isEmpty();
    }

    @Test
    @DisplayName("첨부가 켜져 있으면 한계값이 보존된다")
    void enabledAttachmentPreservesLimits() {
        when(loadPort.existsByKey(anyString())).thenReturn(false);
        when(savePort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardDefinition created = service.create(
                createCommand("photo", new AttachmentSpec(true, 4, 3072, List.of("JPG", ".png")), BoardSkin.GALLERY));

        assertThat(created.getAttachmentPolicy().maxCount()).isEqualTo(4);
        assertThat(created.getAttachmentPolicy().maxSizeKb()).isEqualTo(3072);
        assertThat(created.getAttachmentPolicy().allowedExtensions()).containsExactlyInAnyOrder("jpg", "png");
    }

    @Test
    @DisplayName("스펙이 null 이면 도메인 예외로 거부한다 — NPE 로 새지 않는다")
    void nullSpecsRejected() {
        assertThatThrownBy(() -> service.create(new CreateBoardCommand("notice", "공지", null, BoardSkin.LIST,
                null, new AttachmentSpec(false, 0, 0, List.of()), access())))
                .isInstanceOf(BoardInvariantViolationException.class);

        assertThatThrownBy(() -> service.create(new CreateBoardCommand("notice", "공지", null, BoardSkin.LIST,
                content(true), null, access())))
                .isInstanceOf(BoardInvariantViolationException.class);

        assertThatThrownBy(() -> service.create(new CreateBoardCommand("notice", "공지", null, BoardSkin.LIST,
                content(true), new AttachmentSpec(false, 0, 0, List.of()), null)))
                .isInstanceOf(BoardInvariantViolationException.class);

        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("스킨과 정책이 어긋나면 저장 전에 막힌다 — 중복 검사까지 가지 않는다")
    void invalidSkinCombinationRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.create(
                createCommand("photo", new AttachmentSpec(false, 0, 0, List.of()), BoardSkin.GALLERY)))
                .isInstanceOf(BoardInvariantViolationException.class);

        verify(loadPort, never()).existsByKey(anyString());
        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("수정은 애그리거트에 위임하고 updatedAt 을 현재 시각으로 전진시킨다")
    void updateDelegatesToAggregate() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(existing("notice", true)));
        when(savePort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BoardDefinition updated = service.update(1L, new UpdateBoardCommand("공지", "설명", BoardSkin.FAQ,
                content(false), new AttachmentSpec(false, 0, 0, List.of()), access()));

        assertThat(updated.getName()).isEqualTo("공지");
        assertThat(updated.getSkin()).isEqualTo(BoardSkin.FAQ);
        assertThat(updated.getBoardKey()).isEqualTo("notice");
        assertThat(updated.getUpdatedAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("없는 게시판을 수정하면 404 로 이어질 예외를 던진다")
    void updateMissingBoard() {
        when(loadPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new UpdateBoardCommand("공지", null, BoardSkin.LIST,
                content(true), new AttachmentSpec(false, 0, 0, List.of()), access())))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    @DisplayName("비활성화·활성화는 애그리거트 상태를 바꾸고 저장한다")
    void deactivateAndActivate() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(existing("notice", true)));
        when(savePort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.deactivate(1L).isActive()).isFalse();

        when(loadPort.findById(2L)).thenReturn(Optional.of(existing("notice", false)));
        assertThat(service.activate(2L).isActive()).isTrue();
    }

    @Test
    @DisplayName("운영 중인 게시판은 삭제할 수 없다 — 먼저 닫아야 한다")
    void deleteRequiresInactive() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(existing("notice", true)));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BoardInvariantViolationException.class)
                .hasMessageContaining("비활성화");

        verify(savePort, never()).delete(anyLong());
    }

    @Test
    @DisplayName("닫힌 게시판은 삭제된다")
    void deleteInactive() {
        when(loadPort.findById(1L)).thenReturn(Optional.of(existing("notice", false)));

        service.delete(1L);

        verify(savePort).delete(1L);
    }

    @Test
    @DisplayName("키 조회는 대소문자·공백을 정규화해 넘긴다")
    void getByKeyNormalizes() {
        when(loadPort.findByKey("notice")).thenReturn(Optional.of(existing("notice", true)));

        assertThat(service.getByKey("  NOTICE ").getBoardKey()).isEqualTo("notice");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(loadPort).findByKey(captor.capture());
        assertThat(captor.getValue()).isEqualTo("notice");
    }

    @Test
    @DisplayName("없는 키를 조회하면 원래 입력을 담은 예외를 던진다")
    void getByKeyMissing() {
        when(loadPort.findByKey("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByKey("ghost"))
                .isInstanceOf(BoardNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("관리 조회는 전체를, 이용 조회는 활성만 본다")
    void listSeparation() {
        when(loadPort.findAll()).thenReturn(List.of(existing("a", true), existing("b", false)));
        when(loadPort.findByActive(true)).thenReturn(List.of(existing("a", true)));

        assertThat(service.findAll()).hasSize(2);
        assertThat(service.findActive()).hasSize(1);
    }
}
