-- V20260610090000: 회원 승인 워크플로 (시공관리 플랫폼 전환 1순위)
--
-- 업체 회원/시공기사는 가입 후 관리자 승인을 거쳐야 서비스 이용 가능.
-- 기존 이커머스 사용자는 모두 APPROVED 로 백필(서비스 중단 없이 전환).
--
-- 역할(role) 확장: 기존 USER/ADMIN/MANAGER 에 더해 시공관리 도메인 역할 추가
--   CUSTOMER(일반 고객), COMPANY(업체 회원), TECHNICIAN(시공기사), ADMIN(관리자)
-- 멤버십 상태머신: PENDING → APPROVED → SUSPENDED ; → REJECTED

ALTER TABLE opslab.users
    ADD COLUMN IF NOT EXISTS membership_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';

ALTER TABLE opslab.users
    ADD CONSTRAINT chk_users_membership_status
        CHECK (membership_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'));

-- 승인 대기 회원을 관리자 대시보드에서 빠르게 집계하기 위한 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_users_membership_pending
    ON opslab.users (membership_status)
    WHERE membership_status = 'PENDING';

COMMENT ON COLUMN opslab.users.membership_status IS
    'PENDING=승인대기, APPROVED=승인완료(이용가능), REJECTED=반려, SUSPENDED=정지';

-- 승인/반려/정지 처리 이력 (누가 언제 왜 처리했는지 감사 추적)
CREATE TABLE IF NOT EXISTS opslab.membership_approvals (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    action        VARCHAR(20)  NOT NULL,
    reason        VARCHAR(500),
    processed_by  BIGINT       NOT NULL,                  -- 처리한 관리자 user_id
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_membership_approvals_user
        FOREIGN KEY (user_id) REFERENCES opslab.users(id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_approvals_admin
        FOREIGN KEY (processed_by) REFERENCES opslab.users(id),
    CONSTRAINT chk_membership_approvals_action
        CHECK (action IN ('APPROVE', 'REJECT', 'SUSPEND', 'REINSTATE'))
);

CREATE INDEX IF NOT EXISTS idx_membership_approvals_user
    ON opslab.membership_approvals (user_id, created_at DESC);

COMMENT ON COLUMN opslab.membership_approvals.action IS
    'APPROVE=승인, REJECT=반려, SUSPEND=정지, REINSTATE=정지해제';
