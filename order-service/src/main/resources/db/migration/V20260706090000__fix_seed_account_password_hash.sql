-- V17 시드 계정의 BCrypt 해시가 주석("비밀번호: password123")과 달리 실제로는 password123 과
-- 불일치해 시드 계정 로그인이 항상 401 이었다 (E2E 실기동 검증에서 발견 — 튜토리얼에서 복사된
-- 것으로 보이는 cost 10 해시). 실제 password123 의 해시(앱 인코더와 동일한 BCrypt cost 12)로 정정.
UPDATE users
SET password = '$2a$12$fArbbNi/4MEpB9fPafznVeUPE3UbpQvtyB4/XZc1eyhDn66c9NCUm'
WHERE email LIKE 'seed\_%@test.com' ESCAPE '\';
