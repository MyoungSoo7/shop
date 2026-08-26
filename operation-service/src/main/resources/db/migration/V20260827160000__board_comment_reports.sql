-- ============================================================
-- V20260827160000 : 댓글 신고 접수 + 댓글 가림(HIDDEN) 상태
--
-- dentis 의 관리자 '댓글 관리'(admin/site/report, tb_declaration)를 옮겨 온다. 옮기면서
-- 그쪽 화면의 결함 셋을 고친다.
--
--   ① 큐에 조치가 없었다. dentis 의 처리는 process_yn 을 'Y' 로 바꾸는 것이 전부라, 신고를
--      "처리완료"로 표시해도 문제의 댓글은 화면에 그대로 남았다. 관리자는 그 댓글을 따로 찾아
--      지워야 했고, 큐만 보면 조치가 끝난 것처럼 보였다. 그래서 여기서는 처리 결과를
--      HIDDEN(가림) / KEPT(유지) 둘로 두고, HIDDEN 이면 댓글 상태도 함께 바뀐다.
--   ② 같은 사람이 같은 댓글을 몇 번이고 신고할 수 있었다. 큐가 부풀어 실제 신고 건수를
--      읽을 수 없게 된다 → UNIQUE (comment_id, reporter_id).
--   ③ 신고자·피신고자를 문자열 id 로 들고 다니며 매번 tb_member 를 되짚었다. 여기서는
--      신고자 식별자를 그대로 저장하고, 피신고자는 댓글이 이미 알고 있으므로 중복 저장하지 않는다.
--
-- 가림을 삭제와 별개 상태로 두는 이유: 삭제는 작성자 본인도 하고 되돌릴 수 없는 반면, 가림은
-- 운영이 하고 되돌릴 수 있어야 한다. 오판으로 내린 댓글을 복구할 방법이 없으면 운영은
-- 아예 내리지 않게 되고, 큐는 다시 장식이 된다.
-- ============================================================

-- 1) 댓글 상태에 HIDDEN 추가 (VARCHAR(10) 안에 들어간다 — 6자)
ALTER TABLE board.board_comments DROP CONSTRAINT IF EXISTS ck_board_comment_status;
ALTER TABLE board.board_comments ADD CONSTRAINT ck_board_comment_status
    CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED'));

-- 2) 통합 콘솔은 글이 아니라 "전 게시판의 댓글"을 최신순으로 훑는다 — post_id 선두 인덱스로는
--    그 정렬을 탈 수 없다.
CREATE INDEX IF NOT EXISTS idx_board_comments_recent
    ON board.board_comments(created_at DESC, id DESC);

-- 3) 신고 접수
CREATE TABLE IF NOT EXISTS board.board_comment_reports (
    id            BIGSERIAL    PRIMARY KEY,
    comment_id    BIGINT       NOT NULL REFERENCES board.board_comments(id),
    reporter_id   BIGINT       NOT NULL,
    -- 마스킹된 표시명. 게시글·댓글과 같은 규약이다(원문 이메일은 이 서비스에 두지 않는다).
    reporter_name VARCHAR(40)  NOT NULL,
    reason        VARCHAR(20)  NOT NULL,
    detail        VARCHAR(500),
    status        VARCHAR(10)  NOT NULL DEFAULT 'RECEIVED',
    handled_by    VARCHAR(64),
    handled_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE board.board_comment_reports DROP CONSTRAINT IF EXISTS ck_comment_report_status;
ALTER TABLE board.board_comment_reports ADD CONSTRAINT ck_comment_report_status
    CHECK (status IN ('RECEIVED', 'HIDDEN', 'KEPT'));

ALTER TABLE board.board_comment_reports DROP CONSTRAINT IF EXISTS ck_comment_report_reason;
ALTER TABLE board.board_comment_reports ADD CONSTRAINT ck_comment_report_reason
    CHECK (reason IN ('SPAM', 'ABUSE', 'ADULT', 'PRIVACY', 'ETC'));

-- 처리한 신고는 처리자와 처리 시각이 함께 있어야 한다. 하나만 채워진 행은 감사에서 읽을 수 없다.
ALTER TABLE board.board_comment_reports DROP CONSTRAINT IF EXISTS ck_comment_report_handled;
ALTER TABLE board.board_comment_reports ADD CONSTRAINT ck_comment_report_handled
    CHECK ((status = 'RECEIVED' AND handled_by IS NULL AND handled_at IS NULL)
        OR (status <> 'RECEIVED' AND handled_by IS NOT NULL AND handled_at IS NOT NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uk_comment_report_once
    ON board.board_comment_reports(comment_id, reporter_id);

-- 큐의 기본 화면은 "미처리를 오래된 순으로" 본다 — 먼저 들어온 신고가 먼저 처리돼야 한다.
CREATE INDEX IF NOT EXISTS idx_comment_report_queue
    ON board.board_comment_reports(status, created_at);
