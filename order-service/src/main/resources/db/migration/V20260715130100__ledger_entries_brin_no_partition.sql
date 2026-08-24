-- V20260715130100: ledger_entries 비파티션 결정 + BRIN(created_at) 인덱스
--
-- [왜 파티셔닝하지 않는가]
--   ledger_entries 는 복식부기 원장이다. 멱등·중복분개 방어선이 전역 유니크/참조 제약(reference_id·
--   reference_type 기반 대사, POSTED 불변)에 의존하는데, RANGE 파티셔닝은 파티션 키를 모든 유니크
--   제약에 강제로 포함시켜야 하므로(파티션드 테이블 제약) 그 전역 유일성이 파티션 로컬로 쪼개져 훼손된다.
--   원장은 append-only·불변이라 리텐션 삭제 대상도 아니다 → 파티셔닝 이득(리텐션 DROP·프루닝)이 없고
--   회계 무결성 리스크만 남는다. 따라서 파티셔닝하지 않는다.
-- [대신 무엇을 하는가]
--   원장은 created_at 이 물리 삽입순과 강하게 상관(append-only)하므로, 기간 스캔(시산표·조회)에는
--   B-Tree 대비 저장·유지비용이 훨씬 작은 BRIN 이 최적이다. 블록 범위 요약만 유지해 대용량에서 강력하다.

CREATE INDEX IF NOT EXISTS idx_ledger_entries_created_brin
    ON opslab.ledger_entries USING BRIN (created_at);

COMMENT ON TABLE opslab.ledger_entries IS '원장 항목(복식부기, append-only·불변). 파티셔닝 제외 결정: 전역 유니크/대사 무결성이 파티션 키 강제로 훼손되고 불변이라 리텐션 이득도 없음. 기간 스캔은 BRIN(created_at)으로 커버.';
