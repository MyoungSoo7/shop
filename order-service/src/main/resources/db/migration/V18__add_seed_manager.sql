-- V18: 시드 데이터 추가 (매니저 사용자)
INSERT INTO users (email, password, role, created_at)
VALUES ('seed_manager@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'MANAGER', NOW() - INTERVAL '175 days')
ON CONFLICT (email) DO NOTHING;
