-- ============================================================
-- V1 : board_definitions — 메타 주도 게시판 플랫폼의 뿌리 테이블 (board-service 흡수 이관)
--
-- 이 테이블의 1행 = 게시판 1개다. 프론트의 단일 라우트 /boards/:boardKey 가 이 행을 읽어
-- 스킨을 바꿔 그리므로, 게시판을 늘리는 데 배포도 마이그레이션도 필요 없다.
--
-- 테이블은 자체 board 스키마를 유지한다 — 엔티티가 @Table(schema = "board") 로 명시 매핑하므로
-- opslab 기본 스키마와 섞이지 않고, 구 DB(lemuel_board)의 board 스키마를 1:1 로 복사하는
-- 데이터 이관도 스키마 이름 그대로 성립한다(education 슬라이스와 같은 방식).
--
-- 역할 allowlist·허용 확장자를 쉼표 결합 문자열로 두는 이유는 엔티티 javadoc 참조:
-- 값이 한 자리 개수이고, 부분 조회를 SQL 로 할 일이 없다(판정은 항상 도메인이 한다).
-- ============================================================

CREATE SCHEMA IF NOT EXISTS board;

CREATE TABLE IF NOT EXISTS board.board_definitions (
    id                     BIGSERIAL     PRIMARY KEY,
    board_key              VARCHAR(40)   NOT NULL UNIQUE,
    name                   VARCHAR(100)  NOT NULL,
    description            VARCHAR(300),
    skin                   VARCHAR(10)   NOT NULL,
    content_format         VARCHAR(10)   NOT NULL,
    category_group_code    VARCHAR(40),
    comments_enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    secret_enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    attachments_enabled    BOOLEAN       NOT NULL DEFAULT FALSE,
    max_attachment_count   INT           NOT NULL DEFAULT 0,
    max_attachment_size_kb INT           NOT NULL DEFAULT 0,
    allowed_extensions     VARCHAR(200),
    read_roles             VARCHAR(200),
    write_roles            VARCHAR(200),
    comment_roles          VARCHAR(200),
    manage_roles           VARCHAR(200),
    active                 BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 스킨·본문형식은 도메인 enum 과 1:1 이다. DB 제약을 함께 두는 이유는 수기 INSERT 나 복구
-- 스크립트가 렌더링되지 않는 값을 심는 사고를 막기 위해서다(MenuArea 의 ck_menus_area 와 같은 취지).
ALTER TABLE board.board_definitions DROP CONSTRAINT IF EXISTS ck_board_skin;
ALTER TABLE board.board_definitions ADD CONSTRAINT ck_board_skin
    CHECK (skin IN ('LIST', 'GALLERY', 'FAQ', 'QNA'));

ALTER TABLE board.board_definitions DROP CONSTRAINT IF EXISTS ck_board_content_format;
ALTER TABLE board.board_definitions ADD CONSTRAINT ck_board_content_format
    CHECK (content_format IN ('TEXT', 'MARKDOWN', 'HTML'));

-- 이용 목록 조회는 항상 "활성만, 이름순"이다.
CREATE INDEX IF NOT EXISTS idx_board_definitions_active ON board.board_definitions(active, name);

-- ============================================================
-- 시드: 기본 게시판 2개
--   notice  — 공개 읽기(비로그인 포함) + 관리자 쓰기. LIST 스킨의 표준형.
--   gallery — 이미지 게시판. GALLERY 스킨은 첨부를 끌 수 없다(도메인 불변식).
-- ============================================================
INSERT INTO board.board_definitions (
    board_key, name, description, skin, content_format,
    comments_enabled, secret_enabled,
    attachments_enabled, max_attachment_count, max_attachment_size_kb, allowed_extensions,
    read_roles, write_roles, comment_roles, manage_roles, active
) VALUES
    ('notice', '공지사항', '서비스 공지 및 안내', 'LIST', 'MARKDOWN',
     FALSE, FALSE,
     TRUE, 5, 5120, 'jpg,jpeg,png,pdf',
     NULL, 'ADMIN', 'ADMIN', 'ADMIN', TRUE),
    ('gallery', '포토 갤러리', '이미지 게시판', 'GALLERY', 'TEXT',
     TRUE, FALSE,
     TRUE, 10, 5120, 'jpg,jpeg,png,webp,gif',
     NULL, 'ADMIN,MANAGER', 'ADMIN,MANAGER,USER', 'ADMIN', TRUE)
ON CONFLICT (board_key) DO NOTHING;
