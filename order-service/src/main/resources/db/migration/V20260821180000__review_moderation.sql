-- 리뷰 블라인드(노출 차단) 컬럼.
--
-- [왜 삭제가 아니라 블라인드인가]
--   신고된 리뷰를 DELETE 로 지우면 "왜 사라졌는지"를 나중에 설명할 수 없고, 오판이었을 때
--   되돌릴 원문도 남지 않는다. 원문은 보존하고 노출만 끊는다 — 이의 제기에 답할 수 있고
--   복구도 UPDATE 한 번이다.
--
-- [기존 행의 기본값]
--   status DEFAULT 'VISIBLE' + NOT NULL 이다. 이 컬럼이 없던 시절의 리뷰는 전부 공개였으므로
--   기본값을 VISIBLE 로 채워야 과거 리뷰가 한꺼번에 사라지지 않는다. (도메인·어댑터도 null 을
--   VISIBLE 로 읽어 같은 규약을 이중으로 지킨다.)
--
-- [타입]
--   엔티티가 @Column(length = 20) 인 String 이므로 VARCHAR(20) 이어야 한다 —
--   ddl-auto=validate 는 기대 타입이 어긋나면 기동 자체를 막는다.

ALTER TABLE opslab.reviews
    ADD COLUMN IF NOT EXISTS status        VARCHAR(20)  NOT NULL DEFAULT 'VISIBLE',
    ADD COLUMN IF NOT EXISTS hidden_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS hidden_by     BIGINT,
    ADD COLUMN IF NOT EXISTS hidden_at     TIMESTAMP;

ALTER TABLE opslab.reviews
    DROP CONSTRAINT IF EXISTS chk_reviews_status;
ALTER TABLE opslab.reviews
    ADD CONSTRAINT chk_reviews_status CHECK (status IN ('VISIBLE', 'HIDDEN'));

-- 공개 조회는 상품별 목록이고, 관리 콘솔은 "숨겨진 것만" 또는 "최근 순"으로 훑는다.
-- 부분 인덱스로 두면 공개 리뷰가 대부분인 현실에서 인덱스가 작게 유지된다.
CREATE INDEX IF NOT EXISTS idx_reviews_product_status
    ON opslab.reviews (product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reviews_hidden
    ON opslab.reviews (hidden_at DESC)
    WHERE status = 'HIDDEN';

COMMENT ON COLUMN opslab.reviews.status IS
    'VISIBLE|HIDDEN — 블라인드는 원문 보존 + 노출 차단이다(삭제 아님). 공개 조회 경로가 HIDDEN 을 거른다.';
COMMENT ON COLUMN opslab.reviews.hidden_reason IS
    '블라인드 사유. 작성자 이의 제기와 감사 양쪽에 필요해 필수로 받는다.';
