package github.lms.lemuel.operation.board.adapter.in.web.dto;

import github.lms.lemuel.operation.board.domain.BoardActor;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardPost;
import github.lms.lemuel.operation.board.domain.BoardPostStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 게시글 응답.
 *
 * <p>목록형({@link #summary})은 <b>본문을 싣지 않는다</b>. 목록 한 쪽이 본문 50,000자 × 20건이
 * 되면 응답이 메가바이트 단위로 부푼다.
 *
 * <p>{@code editable} 은 화면이 버튼을 그릴지 정하는 힌트일 뿐이다 — 실제 인가는 언제나 서버가
 * 다시 한다. 이 값을 신뢰해 서버 검사를 빼면 그 순간 응답 조작으로 남의 글을 고칠 수 있게 된다.
 */
public record BoardPostResponse(
        Long id,
        Long boardId,
        String categoryCode,
        String title,
        String content,
        BoardContentFormat contentFormat,
        String authorName,
        boolean mine,
        boolean editable,
        boolean pinned,
        boolean secret,
        BoardPostStatus status,
        long viewCount,
        /** 갤러리 목록용 대표 이미지 주소. 이미지 첨부가 없으면 null — 화면이 자리표시를 그린다 */
        String thumbnailUrl,
        /** QNA 목록의 '답변 대기/완료' 판정용. 살아 있는 댓글 수 */
        int commentCount,
        /**
         * 상세에만 실린다(목록은 빈 목록).
         *
         * <p><b>게시판이 첨부를 꺼도 이미 붙은 첨부는 그대로 실어 보낸다.</b> 정책은 미래를 향하므로
         * 새 업로드만 막히고 기존 파일은 남는데, 화면이 그걸 못 받으면 데이터는 있는데 아무도 못 보는
         * 상태가 된다(직링크로는 여전히 받아진다 — 그 어긋남이 G-6 이었다).
         */
        List<BoardAttachmentResponse> attachments,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static BoardPostResponse detail(BoardPost post, BoardActor actor, boolean canManage,
                                           List<BoardAttachmentResponse> attachments) {
        return of(post, actor, canManage, true, null, 0, attachments);
    }

    public static BoardPostResponse summary(BoardPost post, BoardActor actor, boolean canManage,
                                            String thumbnailUrl, int commentCount) {
        return of(post, actor, canManage, false, thumbnailUrl, commentCount, List.of());
    }

    private static BoardPostResponse of(BoardPost post, BoardActor actor, boolean canManage,
                                        boolean withContent, String thumbnailUrl, int commentCount,
                                        List<BoardAttachmentResponse> attachments) {
        boolean mine = actor.owns(post.getAuthor().userId());
        return new BoardPostResponse(
                post.getId(),
                post.getBoardId(),
                post.getCategoryCode(),
                post.getTitle(),
                withContent ? post.getContent() : null,
                post.getContentFormat(),
                post.getAuthor().displayName(),
                mine,
                mine || canManage,
                post.isPinned(),
                post.isSecret(),
                post.getStatus(),
                post.getViewCount(),
                thumbnailUrl,
                commentCount,
                attachments,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }
}
