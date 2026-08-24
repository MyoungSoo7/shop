-- 조직·멤버십 코어 — organization 슬라이스를 order-service(opslab) 로 이관 (ADR 0042)
--
-- [왜 order 인가]
--   organization 은 셀러/기업이라는 **조직 정체성의 마스터**다. 사람(user) 마스터가 이미 order 의
--   opslab 스키마에 있으므로, 조직 마스터도 같은 정체성 축에 둔다. 운영관제(operation)로 보내는 안은
--   "관리자 화면에서 관리된다"는 UI 표면 기준이라 바운디드 컨텍스트 기준이 아니었다(ADR 0042 §대안 검토).
--
-- [원본]
--   organization-service 자체 DB(lemuel_organization)의 V1 을 opslab 스키마로 옮긴 것이다. 스키마 한정
--   `opslab.` 을 명시한다 — order 의 JdbcTemplate 원시 SQL 은 search_path 에 의존하므로 미한정 객체는
--   배포 후에야 터진다(order 마이그레이션 관례).
--
-- [함께 옮기지 않은 것]
--   원본의 audit_logs 파티셔닝 마이그레이션과 outbox/processed_events 는 가져오지 않는다 —
--   order 에 이미 하드닝된 audit_logs(V34 + V20260715130000 파티셔닝)와 Outbox 인프라가 있다.
--   같은 이유로 organization 슬라이스의 PartitionMaintenanceRunner 도 삭제했다(order 의
--   PartitionMaintenanceScheduler 와 중복 — 같은 DB 의 같은 함수를 두 빈이 호출하게 된다).

CREATE TABLE IF NOT EXISTS opslab.organizations (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    type          VARCHAR(20)  NOT NULL,                    -- SELLER / CORPORATE
    external_ref  VARCHAR(64),                              -- sellerId 또는 stockCode (nullable, 비검증 참조)
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / SUSPENDED
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       BIGINT       NOT NULL DEFAULT 0,          -- @Version (동시 수정 경쟁 방지)

    CONSTRAINT chk_org_type   CHECK (type   IN ('SELLER', 'CORPORATE')),
    CONSTRAINT chk_org_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_org_external_ref ON opslab.organizations (external_ref);

CREATE TABLE IF NOT EXISTS opslab.memberships (
    id               BIGSERIAL    PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES opslab.organizations(id),
    user_id          BIGINT       NOT NULL,                  -- 비검증 비즈니스 키
    role             VARCHAR(20)  NOT NULL,                  -- OWNER / MANAGER / STAFF
    status           VARCHAR(20)  NOT NULL DEFAULT 'INVITED',-- INVITED / ACTIVE / SUSPENDED / REMOVED
    invited_by       BIGINT,                                 -- 초대 주체 user_id (생성자 자동 OWNER 는 self)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_member_role   CHECK (role   IN ('OWNER', 'MANAGER', 'STAFF')),
    CONSTRAINT chk_member_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REMOVED'))
);

-- ★ 핵심 불변식: 같은 조직에 같은 user 의 "활성 슬롯"(초대 대기 INVITED + 참여 ACTIVE)은 최대 1건.
--   SUSPENDED/REMOVED 는 슬롯을 비우므로 재초대가 가능하다. 동시 초대 경쟁은 이 인덱스가 최종 차단한다.
--   도메인의 occupiesActiveSlot() 과 이 부분 인덱스는 반드시 같은 상태 집합이어야 한다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_membership_active
    ON opslab.memberships (organization_id, user_id)
    WHERE status IN ('INVITED', 'ACTIVE');

-- 조직별 멤버 목록 조회
CREATE INDEX IF NOT EXISTS idx_membership_org ON opslab.memberships (organization_id, status);
-- 특정 user 가 속한 조직 조회 (인가 판정 시 caller 역할 lookup)
CREATE INDEX IF NOT EXISTS idx_membership_user ON opslab.memberships (user_id, status);
