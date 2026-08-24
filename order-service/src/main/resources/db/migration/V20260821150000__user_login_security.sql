-- 로그인 무차별 대입 잠금 + 비밀번호 사용 기한.
--
-- 레거시 커머스(ssgb2e-front `LoginServiceImpl.selectLogin`)는 두 가지를 로그인 흐름에서 강제했다:
-- `mbpw_chk >= 5` 면 입장 차단, 마지막 비밀번호 변경 후 90 일이 지나면 변경 화면으로 강제 이동.
-- Lemuel 로그인에는 둘 다 없었다 — 시도 횟수를 세지 않으니 온라인 사전 공격이 무제한이었다.

ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP;

-- 기존 계정의 기준 시각을 created_at 이 아니라 NOW() 로 잡는다. created_at 을 쓰면
-- 이 마이그레이션이 도는 순간 90 일 넘은 계정 전부가 즉시 로그인 불가가 된다(시드·데모 계정 포함).
-- "정책 시행일부터 90 일"이 실제로 의도한 유예다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP NOT NULL DEFAULT NOW();

-- 잠긴 계정 조회(운영 콘솔·모니터링). 잠긴 계정은 소수라 부분 인덱스가 맞다.
CREATE INDEX IF NOT EXISTS idx_users_locked_until
    ON users (locked_until)
    WHERE locked_until IS NOT NULL;

COMMENT ON COLUMN users.failed_login_attempts IS '연속 로그인 실패 횟수. 성공 시 0 으로 초기화';
COMMENT ON COLUMN users.locked_until IS '기한부 잠금 해제 시각. NULL 이거나 과거면 잠기지 않음';
COMMENT ON COLUMN users.password_changed_at IS '마지막 비밀번호 변경 시각. 사용 기한(기본 90일) 기준';
