-- ============================================================
-- V20260827140000 : 사이트 팝업 (dentis tb_popup 이식)
--
-- dentis 의 tb_popup 은 board_no 를 PK 로 쓰고 그 값을 INSERT 안에서
-- `(select ifnull(max(board_no),0)+1 from tb_popup)` 로 발급했다 — 동시 등록이면 두 행이 같은
-- 번호를 노린다. 여기서는 UUID 를 애플리케이션이 만든다.
--
-- 이미지: dentis 는 file_master_code 로 tb_file 을 조인했다. shop 의 첨부 테이블
-- (board.board_attachments)은 post_id 가 NOT NULL 이라 글에 딸린 파일만 담는다 — 팝업은 글이
-- 아니므로 재사용할 수 없다. 그래서 이미지 주소를 문자열로 든다. 별도 파일 서브시스템을
-- 새로 만드는 것보다 경계가 정직하다.
--
-- 노출 구간: dentis 의 공개 조회는 `use_yn='Y' AND end_date > now()` 만 봤다 — <b>start_date 를
-- 안 본다</b>. 다음 달로 예약한 팝업이 저장하자마자 떴다는 뜻이다. 여기서는 시작·종료를 둘 다
-- 보며(도메인 isVisibleAt), 종료가 시작보다 빠른 저장 자체를 막는다(dentis 는 막지 않았다 —
-- 그렇게 저장된 팝업은 영영 안 뜨는데 오류도 안 났다).
-- ============================================================

CREATE SCHEMA IF NOT EXISTS site;

CREATE TABLE site.site_popups (
    id                 UUID          PRIMARY KEY,
    title              VARCHAR(200)  NOT NULL,
    image_url          VARCHAR(500),
    link_url           VARCHAR(500),
    -- 링크를 새 창으로 열 것인가(dentis new_yn). 같은 창이면 팝업을 닫는 것과 구분이 안 된다.
    open_in_new_window BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_at          TIMESTAMPTZ   NOT NULL,
    ends_at            TIMESTAMPTZ   NOT NULL,
    sort_order         INT           NOT NULL DEFAULT 0,
    -- active 는 "지금 띄우는가"(dentis use_yn), deleted 는 "목록에서 뺐는가"(delete_yn).
    -- 합치면 '잠시 내림'과 '치움'이 구분되지 않는다 — 강사 명부와 같은 이유다.
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    deleted            BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted_at         TIMESTAMPTZ,
    created_by         VARCHAR(100)  NOT NULL,
    updated_by         VARCHAR(100)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT site_popups_window_ck CHECK (ends_at > starts_at)
);

-- 공개 조회는 "지금 떠야 하는 팝업"을 시간으로 훑는다. 관리 목록은 같은 인덱스의 앞부분을 쓴다.
CREATE INDEX site_popups_window_idx
    ON site.site_popups (deleted, active, starts_at, ends_at);

CREATE INDEX site_popups_sort_idx ON site.site_popups (deleted, sort_order, created_at);
