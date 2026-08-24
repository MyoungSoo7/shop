-- V20260616150000: settlement 소유 사용자 프로젝션 (ADR 0020 Phase 3b, CQRS read model)
--
-- settlement 가 order 의 users 테이블(email)을 @Immutable 매핑(SettlementUserReadModel)하던 것을
-- 대체하기 위한 자체 프로젝션. UserRegistered 이벤트를 consume 해 적재한다.
-- QueryDSL 검색/승인조회가 Phase 3b 컷오버에서 이 테이블의 email 을 사용한다.
-- 현재는 opslab 공유 DB(소유는 settlement), Phase 4 에서 settlement_db 로 이전.

CREATE TABLE IF NOT EXISTS opslab.settlement_user_view (
    user_id    BIGINT       PRIMARY KEY,                 -- order users.id
    email      VARCHAR(255),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE opslab.settlement_user_view IS
    'settlement 소유 사용자 프로젝션 — UserRegistered 이벤트로 적재(ADR 0020 Phase 3b). order users(email) 직접 매핑 대체.';
