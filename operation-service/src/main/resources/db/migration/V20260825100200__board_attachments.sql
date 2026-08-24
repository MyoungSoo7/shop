-- ============================================================
-- V3 : board_attachments — 게시글 첨부 (board 스키마 — V1 주석 참조)
--
-- 저장 파일명(stored_name)은 서버가 만든 UUID 다. 업로더가 준 이름(original_name)은 표시용으로만
-- 남고 경로에는 한 글자도 들어가지 않는다 — 경로 조작을 막는 가장 확실한 방법은 입력을 정화하는
-- 것이 아니라 입력을 쓰지 않는 것이다.
--
-- content_type 도 요청 헤더가 아니라 서버가 매직바이트로 판정한 값이다. 다운로드 응답이 이 값을
-- 그대로 쓰므로, 클라이언트 값을 저장하면 업로더가 응답 헤더를 정하는 셈이 된다.
-- ============================================================

CREATE TABLE IF NOT EXISTS board.board_attachments (
    id            BIGSERIAL     PRIMARY KEY,
    post_id       BIGINT        NOT NULL REFERENCES board.board_posts(id),
    -- 글을 거치지 않고도 "어느 게시판의 첨부인가"를 대조하기 위해 함께 든다(경로 위조 차단).
    board_id      BIGINT        NOT NULL REFERENCES board.board_definitions(id),
    kind          VARCHAR(10)   NOT NULL,
    original_name VARCHAR(200)  NOT NULL,
    stored_name   VARCHAR(100)  NOT NULL,
    storage_path  VARCHAR(300)  NOT NULL,
    content_type  VARCHAR(100)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    sort_order    INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

ALTER TABLE board.board_attachments DROP CONSTRAINT IF EXISTS ck_board_attachment_kind;
ALTER TABLE board.board_attachments ADD CONSTRAINT ck_board_attachment_kind
    CHECK (kind IN ('IMAGE', 'FILE'));

ALTER TABLE board.board_attachments DROP CONSTRAINT IF EXISTS ck_board_attachment_size;
ALTER TABLE board.board_attachments ADD CONSTRAINT ck_board_attachment_size
    CHECK (size_bytes > 0);

-- 조회는 언제나 "이 글의 첨부를 정렬순으로"다.
CREATE INDEX IF NOT EXISTS idx_board_attachments_post ON board.board_attachments(post_id, sort_order, id);
