-- ============================================================
-- V20260813100000 : menus 스키마 확장 + 현행 네비게이션 전량 시드
--
-- 배경: menus 테이블/CRUD API/관리화면은 있었으나 어떤 레이아웃도 이 테이블을 읽지 않았고
--       (프론트 4개 셸이 네비 31개를 tsx 배열로 하드코딩), 시드는 '시스템 관리' 4행뿐이었다.
--       화면을 더 붙이기 전에 트리를 정본으로 세운다.
--
-- 정책: 구조(parent_id / path / area / required_*)는 이 마이그레이션으로만 바꾼다.
--       운영 화면에서 허용하는 편집은 표시 속성(name / sort_order / visible / icon)까지다.
--       그래야 라우트↔메뉴 정합 게이트가 CI 에서 의미를 갖는다.
-- ============================================================

-- ── 1) 컬럼 확장 ────────────────────────────────────────────
ALTER TABLE menus ADD COLUMN IF NOT EXISTS short_name          VARCHAR(40);
ALTER TABLE menus ADD COLUMN IF NOT EXISTS description         VARCHAR(200);
ALTER TABLE menus ADD COLUMN IF NOT EXISTS area                VARCHAR(20);
ALTER TABLE menus ADD COLUMN IF NOT EXISTS menu_type           VARCHAR(10) NOT NULL DEFAULT 'ITEM';
ALTER TABLE menus ADD COLUMN IF NOT EXISTS required_permission VARCHAR(60);

-- 역할은 단일 값이 아니라 allowlist(예: 'ADMIN,MANAGER') 다. 20자로는 두 개도 빠듯하다.
ALTER TABLE menus ALTER COLUMN required_role TYPE VARCHAR(60);

ALTER TABLE menus DROP CONSTRAINT IF EXISTS ck_menus_area;
ALTER TABLE menus ADD CONSTRAINT ck_menus_area
    CHECK (area IS NULL OR area IN ('SHOP', 'SELLER', 'BACKOFFICE', 'CORP', 'CEO', 'SYSTEM'));

ALTER TABLE menus DROP CONSTRAINT IF EXISTS ck_menus_menu_type;
ALTER TABLE menus ADD CONSTRAINT ck_menus_menu_type
    CHECK (menu_type IN ('GROUP', 'ITEM', 'DIVIDER'));

CREATE INDEX IF NOT EXISTS idx_menus_area ON menus(area);

-- ── 2) 기존 시드 제거 ───────────────────────────────────────
-- V20260623100002 가 넣은 '시스템 관리' 4행만 걷어낸다(자식 → 부모 순, FK 보호).
-- 이 4행은 어떤 화면도 읽지 않던 죽은 데이터라 보존 가치가 없고, 아래에서 같은 경로를
-- 영역·권한까지 채워 다시 넣는다.
DELETE FROM menus WHERE path IN ('/admin/system/menus', '/admin/system/codes', '/admin/system/rbac');
DELETE FROM menus WHERE path = '/admin/system' AND parent_id IS NULL;

-- ── 3) 최상위(상단 네비) ────────────────────────────────────
-- 역할 allowlist 는 프론트 라우트 가드와 1:1 로 맞춘 값이다:
--   ProtectedRoute(인증만)=NULL, AdminManagerRoute='ADMIN,MANAGER', AdminOnlyRoute='ADMIN'.
-- 구매자 메뉴가 'USER' 인 이유: 현행 셸이 관리자에게는 주문/추천 링크를 보여 주지 않는다.
INSERT INTO menus (parent_id, name, short_name, path, icon, description, area, menu_type,
                   sort_order, required_role, required_permission, visible, active)
VALUES
    (NULL, '대시보드',    NULL,     '/admin',                '📊', NULL,                    'BACKOFFICE', 'ITEM',  0, 'ADMIN,MANAGER', NULL, TRUE, TRUE),
    (NULL, '정산',        NULL,     '/admin/settlement',     '💰', 'Settlement',            'BACKOFFICE', 'GROUP', 1, 'ADMIN,MANAGER', NULL, TRUE, TRUE),
    (NULL, '배송',        NULL,     '/admin/shipping',       '🚚', NULL,                    'BACKOFFICE', 'ITEM',  2, 'ADMIN,MANAGER', NULL, TRUE, TRUE),
    (NULL, '승인',        NULL,     '/admin/approvals',      '✅', NULL,                    'BACKOFFICE', 'ITEM',  3, 'ADMIN,MANAGER', NULL, TRUE, TRUE),
    (NULL, 'AI 도우미',   NULL,     '/ai/chat',              '🤖', NULL,                    'BACKOFFICE', 'ITEM',  4, 'ADMIN,MANAGER', NULL, TRUE, TRUE),
    (NULL, 'CEO',         NULL,     '/admin/ceo/insight',    '👔', 'Executive Insights',    'CEO',        'GROUP', 5, 'ADMIN,MANAGER', NULL, TRUE, TRUE),
    (NULL, '시스템 관리', '시스템', '/admin/system/menus',   '⚙️', 'System Administration', 'SYSTEM',     'GROUP', 6, 'ADMIN',         NULL, TRUE, TRUE),
    (NULL, '주문하기',    NULL,     '/order',                '🛒', NULL,                    'SHOP',       'ITEM',  7, 'USER',          NULL, TRUE, TRUE),
    (NULL, '추천받기',    NULL,     '/recommend',            '✨', NULL,                    'SHOP',       'ITEM',  8, 'USER',          NULL, TRUE, TRUE);

-- ── 4) 정산 하위 (좌측 사이드바 4개) ────────────────────────
-- '지급관리'만 ADMIN 인 이유: 서버가 /admin/payouts/** 를 ADMIN 으로 막는다(실자금 송금).
-- MANAGER 에게 보여 주면 눌러도 리다이렉트되는 죽은 링크가 된다.
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, v.name, v.path, v.icon, v.description, 'BACKOFFICE', 'ITEM',
       v.sort_order, v.required_role, TRUE, TRUE
FROM menus p
CROSS JOIN (VALUES
    ('상품관리', '/product',           '📦', '상품 · 재고 관리',      0, 'ADMIN,MANAGER'),
    ('정산관리', '/admin/settlement',  '💰', '정산 생성 · 확정',      1, 'ADMIN,MANAGER'),
    ('정산조회', '/settlement/search', '🔍', '정산 내역 조회',        2, 'ADMIN,MANAGER'),
    ('지급관리', '/admin/payouts',     '🏦', '송금 실행 · 실패 처리', 3, 'ADMIN')
) AS v(name, path, icon, description, sort_order, required_role)
WHERE p.name = '정산' AND p.parent_id IS NULL;

-- ── 5) CEO 하위 (좌측 사이드바 13개) ────────────────────────
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, visible, active)
SELECT p.id, v.name, v.path, v.icon, v.description, 'CEO', 'ITEM',
       v.sort_order, 'ADMIN,MANAGER', TRUE, TRUE
FROM menus p
CROSS JOIN (VALUES
    ('통합 브리핑',          '/admin/ceo/insight',          'CEO', '재무·평판·경제 통합',              0),
    ('경제지표',             '/admin/ceo/economics',        '📈',  '한국은행 ECOS 지표',               1),
    ('재무제표',             '/admin/ceo/financials',       '📊',  '코스피 상장사 재무제표',           2),
    ('기업조회',             '/admin/ceo/companies',        '📰',  '기업 뉴스 · 평판',                 3),
    ('사업장비교',           '/admin/ceo/workforce',        '🏢',  '국민연금 인원 · 연봉',             4),
    ('투자하기',             '/admin/ceo/invest',           '💹',  '재무점수 기반 투자',               5),
    ('투자 추천',            '/admin/ceo/invest-recommend', '🧭',  '규칙 스크리닝 추천 종목',          6),
    ('대출관리',             '/admin/ceo/loans',            '💸',  '선정산 · 기업대출',                7),
    ('대출 상품 안내',       '/admin/ceo/loan-guide',       '📖',  '개인신용 · 주택담보',              8),
    ('대출 심사·상환 안내',  '/admin/ceo/loan-process',     '🧾',  '심사 · 신용평가 · 상환',           9),
    ('대출기관 안내',        '/admin/ceo/lender-guide',     '🏦',  '은행 · 저축은행 · 캐피탈 · 대부업', 10),
    ('자산운용펀드 안내',    '/admin/ceo/fund-guide',       '🧺',  '부동산 · 주식 · 채권 펀드',        11),
    ('계정계 현황',          '/admin/ceo/accounts',         '🧮',  '집계 · 시산표 · 잔액',             12)
) AS v(name, path, icon, description, sort_order)
WHERE p.name = 'CEO' AND p.parent_id IS NULL;

-- ── 6) 시스템 하위 (좌측 사이드바 5개) ──────────────────────
-- 앞의 3개는 V20260623100003 이 시드한 permission 과 짝을 맞춘다. ADMIN 은 전 권한 보유이므로
-- 보이는 결과는 지금과 같고, 권한 축만 실제로 동작하게 된다.
INSERT INTO menus (parent_id, name, path, icon, description, area, menu_type,
                   sort_order, required_role, required_permission, visible, active)
SELECT p.id, v.name, v.path, v.icon, v.description, 'SYSTEM', 'ITEM',
       v.sort_order, 'ADMIN', v.required_permission, TRUE, TRUE
FROM menus p
CROSS JOIN (VALUES
    ('메뉴 관리',         '/admin/system/menus',                 '🗂️', '네비게이션 메뉴 트리', 0, 'SYSTEM_MENU_MANAGE'),
    ('공통코드 관리',     '/admin/system/codes',                 '🏷️', '코드 그룹 / 항목',     1, 'SYSTEM_CODE_MANAGE'),
    ('RBAC 관리',         '/admin/system/rbac',                  '🔐', '역할 · 권한 매트릭스', 2, 'SYSTEM_RBAC_MANAGE'),
    ('이커머스 카테고리', '/admin/system/ecommerce-categories',   '📁', '상품 카테고리 트리',   3, NULL),
    ('운영관리',          '/admin/system/operation',             '🖥️', '인시던트 관제 콘솔',   4, NULL)
) AS v(name, path, icon, description, sort_order, required_permission)
WHERE p.name = '시스템 관리' AND p.parent_id IS NULL;
