-- ============================================================
-- V2 : board_posts / board_comments — 게시글과 댓글 (board 스키마 — V1 주석 참조)
--
-- 게시판(board_definitions)이 규칙을 소유하고, 여기에 그 규칙을 지킨 글이 쌓인다.
-- 두 테이블 모두 삭제를 물리 삭제가 아니라 status 전이로 남긴다 — 댓글이 달린 글을 지우면
-- 대화의 앞말이 사라지고, 신고·감사 대응에서 "무엇이 지워졌는지"를 답할 수 없다.
-- ============================================================

CREATE TABLE IF NOT EXISTS board.board_posts (
    id             BIGSERIAL     PRIMARY KEY,
    board_id       BIGINT        NOT NULL REFERENCES board.board_definitions(id),
    category_code  VARCHAR(40),
    title          VARCHAR(200)  NOT NULL,
    content        TEXT          NOT NULL,
    -- 작성 시점 스냅샷. 게시판 정책이 나중에 TEXT→HTML 로 바뀌어도 이미 쓴 글의 렌더 방식은 그대로다.
    content_format VARCHAR(10)   NOT NULL,
    author_id      BIGINT        NOT NULL,
    -- 마스킹된 표시명(예: 'ad***'). 원문 이메일은 이 서비스에 저장하지 않는다 — 소유권 대조는
    -- author_id 로 하므로 표시명이 마스킹돼도 인가 정확도는 떨어지지 않는다.
    author_name    VARCHAR(40)   NOT NULL,
    pinned         BOOLEAN       NOT NULL DEFAULT FALSE,
    secret         BOOLEAN       NOT NULL DEFAULT FALSE,
    status         VARCHAR(10)   NOT NULL DEFAULT 'PUBLISHED',
    view_count     BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

ALTER TABLE board.board_posts DROP CONSTRAINT IF EXISTS ck_board_post_status;
ALTER TABLE board.board_posts ADD CONSTRAINT ck_board_post_status
    CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED'));

ALTER TABLE board.board_posts DROP CONSTRAINT IF EXISTS ck_board_post_content_format;
ALTER TABLE board.board_posts ADD CONSTRAINT ck_board_post_content_format
    CHECK (content_format IN ('TEXT', 'MARKDOWN', 'HTML'));

-- 목록 조회는 언제나 "이 게시판의, 이 상태인 글을, 고정 먼저 최신순"이다.
-- 정렬까지 인덱스로 흡수되도록 컬럼 순서를 조회 형태에 맞춘다.
CREATE INDEX IF NOT EXISTS idx_board_posts_list
    ON board.board_posts(board_id, status, pinned DESC, created_at DESC);

-- 내 글 찾기(비밀글 소유 판정)는 작성자 기준 조회다.
CREATE INDEX IF NOT EXISTS idx_board_posts_author ON board.board_posts(board_id, author_id);

CREATE TABLE IF NOT EXISTS board.board_comments (
    id          BIGSERIAL     PRIMARY KEY,
    post_id     BIGINT        NOT NULL REFERENCES board.board_posts(id),
    -- 글을 거치지 않고도 "어느 게시판의 댓글인가"를 대조하기 위해 함께 든다(경로 위조 차단).
    board_id    BIGINT        NOT NULL REFERENCES board.board_definitions(id),
    parent_id   BIGINT        REFERENCES board.board_comments(id),
    author_id   BIGINT        NOT NULL,
    author_name VARCHAR(40)   NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    status      VARCHAR(10)   NOT NULL DEFAULT 'PUBLISHED',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

ALTER TABLE board.board_comments DROP CONSTRAINT IF EXISTS ck_board_comment_status;
ALTER TABLE board.board_comments ADD CONSTRAINT ck_board_comment_status
    CHECK (status IN ('PUBLISHED', 'DELETED'));

CREATE INDEX IF NOT EXISTS idx_board_comments_post ON board.board_comments(post_id, id);
