-- V20260718700000: incidents.category 값 CHECK (R5 리뷰 후속, operation)
--
-- [지적 · A-low] incidents 는 status/severity/source 에 CHECK 를 두면서 category(SignalCategory 매핑
--   결과)는 값 제약이 없어 오타·미정의 카테고리가 조용히 적재될 수 있었다. SignalCategory enum
--   11종과 1:1 로 강제한다(매핑 실패는 UNKNOWN 폴백이 정본 — enum 자체에 포함).
-- [service 컬럼 제외 사유] service 는 labels.component **원본 보존** 컬럼(V1 주석) — 값 집합이
--   외부(Alertmanager 라벨) 소유라 CHECK 로 이중 관리하지 않는다(commondata payload 와 동일 원칙).
-- ★ enum 드리프트 주의: SignalCategory 에 값 추가 시 이 CHECK 도 확장할 것(위반 시 런타임 INSERT 실패).

ALTER TABLE incidents
    ADD CONSTRAINT chk_incident_category
        CHECK (category IN ('ORDER_FAILURE','PAYMENT_FAILURE','STOCK_SHORTAGE','SHIPPING_DELAY',
                            'SETTLEMENT_FAILURE','KAFKA_BACKLOG','REDIS_FAILURE','DB_DEADLOCK',
                            'API_TIMEOUT','INFRA_ETC','UNKNOWN')) NOT VALID;
ALTER TABLE incidents VALIDATE CONSTRAINT chk_incident_category;
