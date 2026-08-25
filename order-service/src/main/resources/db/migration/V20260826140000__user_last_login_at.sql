-- 마지막 로그인 성공 시각.
--
-- V20260821150000 이 실패 횟수·잠금·비밀번호 기준 시각을 넣었지만 "마지막으로 성공한 시각"은
-- 어디에도 남지 않았다. 로그인 성공은 failed_login_attempts 를 0 으로 되돌릴 뿐이라, 성공의
-- 흔적이 상태에서 지워지는 구조였다.
--
-- 감사 로그(LOGIN_SUCCESS)로 대신할 수 없다: 그건 사건의 나열이라 "이 계정이 마지막으로 쓰인 게
-- 언제인가"를 물으려면 계정마다 로그 전체를 훑어야 하고, 보존 기간이 지나면 사라진다.
-- 미사용 관리자 계정 정리는 계정 옆에 붙은 값 하나여야 한 번의 조회로 답이 나온다.

-- DEFAULT 를 주지 않는다. NOW() 로 채우면 이 마이그레이션이 도는 순간 전 계정이 "방금 쓴 계정"이
-- 되어, 이 컬럼을 만든 유일한 이유(오래 안 쓴 계정 찾기)가 그 자리에서 무의미해진다.
-- created_at 으로 채우는 것도 같은 종류의 거짓말이다 — 가입은 로그인이 아니다.
-- NULL 은 "모른다"로 남기고, 첫 로그인부터 실제 값이 쌓인다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

-- 운영자 콘솔의 주 조회축: 관리자 역할 계정을 마지막 로그인 오래된 순으로.
-- 전체 users 가 아니라 운영 역할만 걸러 부분 인덱스로 둔다 — 이 질의의 대상은 항상 소수다.
CREATE INDEX IF NOT EXISTS idx_users_operator_last_login
    ON users (last_login_at)
    WHERE role IN ('ADMIN', 'MANAGER');

COMMENT ON COLUMN users.last_login_at IS '마지막 로그인 성공 시각. NULL 은 한 번도 로그인 안 했거나 기록 도입 이전 계정';
