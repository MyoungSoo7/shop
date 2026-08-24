package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 게시판 정의 도메인 테스트.
 *
 * <p>정의는 게시글이 지켜야 할 규칙을 담는 그릇이다 — 그릇이 모순이면(이미지 게시판인데 첨부
 * 불가) 그 위의 모든 글이 검증 불가능해진다. 그래서 조립 시점 차단이 이 클래스의 주 관심사다.
 */
class BoardDefinitionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 15, 9, 0, 0, 0, ZoneOffset.UTC);

    private static BoardContentPolicy content(boolean commentsEnabled) {
        return BoardContentPolicy.of(BoardContentFormat.TEXT, commentsEnabled, false, null);
    }

    private static BoardAttachmentPolicy attachments(boolean enabled) {
        return enabled
                ? BoardAttachmentPolicy.enabled(5, 2048, List.of("jpg", "png"))
                : BoardAttachmentPolicy.disabled();
    }

    private static BoardAccessPolicy access() {
        return BoardAccessPolicy.of(List.of(), List.of("ADMIN"), List.of("USER"), List.of("ADMIN"));
    }

    private static BoardDefinition definition(String boardKey, String name, BoardSkin skin,
                                              boolean comments, boolean attachmentsEnabled) {
        return BoardDefinition.create(boardKey, name, null, skin,
                content(comments), attachments(attachmentsEnabled), access(), NOW);
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("정상 생성 시 키는 소문자로, 설명은 트림되어 정규화된다")
        void normalizesOnCreate() {
            BoardDefinition definition = BoardDefinition.create("  Notice-Board  ", "  공지사항  ", "  안내  ",
                    BoardSkin.LIST, content(true), attachments(false), access(), NOW);

            assertThat(definition.getBoardKey()).isEqualTo("notice-board");
            assertThat(definition.getName()).isEqualTo("공지사항");
            assertThat(definition.getDescription()).isEqualTo("안내");
            assertThat(definition.isActive()).isTrue();
            assertThat(definition.getCreatedAt()).isEqualTo(NOW);
            assertThat(definition.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("설명이 공백뿐이면 null 로 접힌다")
        void blankDescriptionBecomesNull() {
            BoardDefinition definition = BoardDefinition.create("notice", "공지", "   ",
                    BoardSkin.LIST, content(true), attachments(false), access(), NOW);

            assertThat(definition.getDescription()).isNull();
        }

        @ParameterizedTest
        @DisplayName("게시판 키가 URL 세그먼트 규칙을 어기면 거부한다")
        @ValueSource(strings = {"공지사항", "notice board", "notice_board", "-notice", "notice-", "n", "notice/sub", "no+tice"})
        void rejectsInvalidBoardKey(String boardKey) {
            assertThatThrownBy(() -> definition(boardKey, "공지", BoardSkin.LIST, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("게시판 키");
        }

        @ParameterizedTest
        @DisplayName("게시판 키가 비어 있으면 거부한다")
        @ValueSource(strings = {"", "   "})
        void rejectsBlankBoardKey(String boardKey) {
            assertThatThrownBy(() -> definition(boardKey, "공지", BoardSkin.LIST, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("게시판 키가 null 이면 거부한다")
        void rejectsNullBoardKey() {
            assertThatThrownBy(() -> definition(null, "공지", BoardSkin.LIST, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("40자 키는 허용하고 41자는 거부한다 — 경계")
        void boardKeyLengthBoundary() {
            String fortyChars = "a".repeat(40);
            assertThatCode(() -> definition(fortyChars, "공지", BoardSkin.LIST, true, false))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> definition("a".repeat(41), "공지", BoardSkin.LIST, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("게시판명이 비면 거부한다")
        void rejectsBlankName() {
            assertThatThrownBy(() -> definition("notice", "   ", BoardSkin.LIST, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("게시판명");
        }

        @Test
        @DisplayName("게시판명 100자는 허용하고 101자는 거부한다 — 경계")
        void nameLengthBoundary() {
            assertThatCode(() -> definition("notice", "가".repeat(100), BoardSkin.LIST, true, false))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> definition("notice", "가".repeat(101), BoardSkin.LIST, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("설명 300자는 허용하고 301자는 거부한다 — 경계")
        void descriptionLengthBoundary() {
            assertThatCode(() -> BoardDefinition.create("notice", "공지", "설".repeat(300), BoardSkin.LIST,
                    content(true), attachments(false), access(), NOW)).doesNotThrowAnyException();

            assertThatThrownBy(() -> BoardDefinition.create("notice", "공지", "설".repeat(301), BoardSkin.LIST,
                    content(true), attachments(false), access(), NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("스킨이 null 이면 거부한다")
        void rejectsNullSkin() {
            assertThatThrownBy(() -> BoardDefinition.create("notice", "공지", null, null,
                    content(true), attachments(false), access(), NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("정책이 하나라도 null 이면 거부한다")
        void rejectsNullPolicies() {
            assertThatThrownBy(() -> BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                    null, attachments(false), access(), NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);

            assertThatThrownBy(() -> BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                    content(true), null, access(), NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);

            assertThatThrownBy(() -> BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                    content(true), attachments(false), null, NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("생성 시각이 null 이면 거부한다")
        void rejectsNullTimestamp() {
            assertThatThrownBy(() -> BoardDefinition.create("notice", "공지", null, BoardSkin.LIST,
                    content(true), attachments(false), access(), null))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }
    }

    @Nested
    @DisplayName("스킨과 정책의 정합")
    class SkinConsistency {

        @Test
        @DisplayName("GALLERY 스킨인데 첨부가 꺼져 있으면 거부한다 — 썸네일 없는 그리드는 빈 칸만 남는다")
        void galleryRequiresAttachments() {
            assertThatThrownBy(() -> definition("photo", "포토", BoardSkin.GALLERY, true, false))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("첨부");
        }

        @Test
        @DisplayName("GALLERY 스킨에 첨부가 켜져 있으면 통과한다")
        void galleryWithAttachmentsPasses() {
            assertThatCode(() -> definition("photo", "포토", BoardSkin.GALLERY, true, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("QNA 스킨인데 댓글이 꺼져 있으면 거부한다 — 답할 수단 없는 질문 게시판이 된다")
        void qnaRequiresComments() {
            assertThatThrownBy(() -> definition("qna", "문의", BoardSkin.QNA, false, false))
                    .isInstanceOf(BoardInvariantViolationException.class)
                    .hasMessageContaining("댓글");
        }

        @Test
        @DisplayName("LIST·FAQ 는 첨부·댓글을 모두 꺼도 성립한다")
        void listAndFaqAreUnconstrained() {
            assertThatCode(() -> definition("notice", "공지", BoardSkin.LIST, false, false))
                    .doesNotThrowAnyException();
            assertThatCode(() -> definition("faq", "FAQ", BoardSkin.FAQ, false, false))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("수정도 생성과 같은 불변식을 강제한다 — 사후 우회 경로를 만들지 않는다")
        void updateEnforcesSameInvariants() {
            BoardDefinition definition = definition("photo", "포토", BoardSkin.GALLERY, true, true);

            assertThatThrownBy(() -> definition.update("포토", null, BoardSkin.GALLERY,
                    content(true), attachments(false), access(), NOW))
                    .isInstanceOf(BoardInvariantViolationException.class);

            // 실패한 수정이 애그리거트를 반쯤 바꿔 놓지 않았는지 — 첨부 정책은 원래대로여야 한다
            assertThat(definition.getAttachmentPolicy().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("수정은 키를 바꾸지 않고 updatedAt 만 전진시킨다")
        void updateKeepsKey() {
            BoardDefinition definition = definition("notice", "공지", BoardSkin.LIST, true, false);
            OffsetDateTime later = NOW.plusHours(3);

            definition.update("공지사항", "설명", BoardSkin.FAQ, content(false), attachments(false), access(), later);

            assertThat(definition.getBoardKey()).isEqualTo("notice");
            assertThat(definition.getName()).isEqualTo("공지사항");
            assertThat(definition.getSkin()).isEqualTo(BoardSkin.FAQ);
            assertThat(definition.getUpdatedAt()).isEqualTo(later);
            assertThat(definition.getCreatedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("활성 상태")
    class Activation {

        @Test
        @DisplayName("비활성화는 한 번만 — 이미 닫힌 게시판을 다시 닫으면 거부한다")
        void deactivateIsIdempotentGuarded() {
            BoardDefinition definition = definition("notice", "공지", BoardSkin.LIST, true, false);
            definition.deactivate(NOW.plusDays(1));

            assertThat(definition.isActive()).isFalse();
            assertThatThrownBy(() -> definition.deactivate(NOW.plusDays(2)))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("활성 상태에서 다시 활성화하면 거부한다")
        void activateOnActiveRejected() {
            BoardDefinition definition = definition("notice", "공지", BoardSkin.LIST, true, false);

            assertThatThrownBy(() -> definition.activate(NOW.plusDays(1)))
                    .isInstanceOf(BoardInvariantViolationException.class);
        }

        @Test
        @DisplayName("닫은 게시판은 다시 열 수 있다")
        void reactivate() {
            BoardDefinition definition = definition("notice", "공지", BoardSkin.LIST, true, false);
            definition.deactivate(NOW.plusDays(1));
            definition.activate(NOW.plusDays(2));

            assertThat(definition.isActive()).isTrue();
            assertThat(definition.getUpdatedAt()).isEqualTo(NOW.plusDays(2));
        }
    }

    @Nested
    @DisplayName("경로와 인가")
    class PathAndAccess {

        @Test
        @DisplayName("경로는 /boards/{key} — 메뉴 연결이 이 값을 그대로 쓴다")
        void pathIsDerivedFromKey() {
            assertThat(definition("notice", "공지", BoardSkin.LIST, true, false).path())
                    .isEqualTo("/boards/notice");
        }

        @Test
        @DisplayName("댓글이 꺼진 게시판은 역할이 허용돼도 댓글을 쓸 수 없다")
        void commentsDisabledOverridesRole() {
            BoardDefinition commentable = definition("notice", "공지", BoardSkin.LIST, true, false);
            BoardDefinition silent = definition("notice", "공지", BoardSkin.LIST, false, false);

            assertThat(commentable.canComment("USER")).isTrue();
            assertThat(silent.canComment("USER")).isFalse();
        }

        @Test
        @DisplayName("읽기 역할이 비면 공개 게시판이라 미인증(null 역할)도 읽는다")
        void publicReadAllowsAnonymous() {
            BoardDefinition definition = definition("notice", "공지", BoardSkin.LIST, true, false);

            assertThat(definition.canRead(null)).isTrue();
            assertThat(definition.canWrite(null)).isFalse();
            assertThat(definition.canWrite("ADMIN")).isTrue();
            assertThat(definition.canManage("USER")).isFalse();
        }
    }

    @Test
    @DisplayName("rehydrate 는 저장값을 그대로 복원한다 — 정책이 강화돼도 기존 게시판 조회가 죽지 않는다")
    void rehydrateDoesNotRevalidate() {
        BoardDefinition definition = BoardDefinition.rehydrate(7L, "LEGACY_KEY", "옛 게시판", null,
                BoardSkin.GALLERY, BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, false, false, null),
                BoardAttachmentPolicy.rehydrate(false, 0, 0, Set.of()),
                BoardAccessPolicy.rehydrate(Set.of(), Set.of(), Set.of(), Set.of()),
                false, NOW, NOW);

        assertThat(definition.getId()).isEqualTo(7L);
        assertThat(definition.getBoardKey()).isEqualTo("LEGACY_KEY");
        assertThat(definition.isActive()).isFalse();
    }
}
