-- V20260820120000: 시드 거래를 셀러 3인(등급 3종)에 흩어 등급별 정산 분기를 실제로 태운다
--
-- [문제]
--   V31 은 시드 상품 20개를 전부 seed_manager@test.com 한 명에게 붙였다. 그래서 V17 이 만든
--   주문·결제 1,000건이 단일 셀러·단일 등급(NORMAL)으로만 흐른다. 등급별 수수료
--   (NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%)·정산주기(T+7/T+3/T+1)·홀드백(30%/30일,
--   10%/14일, 0%) 이 코드에 다 있어도 시드 데이터가 그 분기에 닿지 않는다. 정책이 있는 것과
--   그 경로가 한 번이라도 실행되는 것은 다른 문제다 — 데모·수동확인·부하생성 모두 NORMAL 만 본다.
--
-- [조치]
--   VIP·STRATEGIC 셀러를 추가하고 기존 상품 20개를 셀러 3인에게 나눠 준다. 주문은 product_id 로
--   셀러가 결정되므로, 상품을 옮기는 것만으로 기존 1,000건이 세 등급에 자동으로 걸린다.
--   (주문 행을 직접 건드리지 않는다 — 결제·정산과 얽힌 이력을 시드 정리 목적으로 고쳐 쓰면
--    금액 합계가 어긋날 수 있다. 소유자만 바꾸면 금액은 그대로 보존된다.)
--
-- [배분 규칙] id 순 20개를 3분할한다. 정확한 개수보다 "각 등급이 충분한 표본을 갖는 것"이 중요하다.
--   STRATEGIC 7개(고가 상품 쏠림 방지를 위해 id 오름차순 3n)  · VIP 7개 · NORMAL(seed_manager) 6개
--
-- 멱등: 계정은 ON CONFLICT(email), 등급/소유자는 조건부 UPDATE, assignment 는 ON CONFLICT(seller_id).

-- 1) 셀러 계정 2인 추가. 비밀번호 해시는 리터럴로 적지 않고 기존 시드 계정에서 그대로 가져온다.
--    이유 둘:
--      (a) 해시를 또 적으면 V17 이 겪은 사고가 재현될 수 있다 — 주석은 password123 인데 실제 해시가
--          달라 시드 계정 로그인이 항상 401 이었고, V20260706090000 이 뒤늦게 정정했다. 원본을
--          복사하면 그 정정이 자동으로 따라오고 두 곳이 어긋날 수 없다.
--      (b) BCrypt 해시 리터럴은 SAST(semgrep detected-bcrypt-hash)가 차단한다. 시드용 더미라도
--          "해시가 소스에 박혀 있다"는 사실 자체가 규칙 대상이라, 예외를 다는 것보다 안 적는 게 맞다.
--    seed_manager 가 없으면(=V17/V18 미적용) 아무것도 넣지 않는다. password 는 NOT NULL 이라
--    서브쿼리가 NULL 이면 실패하므로, EXISTS 로 먼저 막는다. 누락되면 SeedSellerTierSpreadIT 가 잡는다.
INSERT INTO opslab.users (email, password, role, created_at)
SELECT v.email,
       (SELECT u.password FROM opslab.users u WHERE u.email = 'seed_manager@test.com'),
       'MANAGER',
       NOW() - INTERVAL '170 days'
  FROM (VALUES ('seed_seller_vip@test.com'), ('seed_seller_strategic@test.com')) AS v(email)
 WHERE EXISTS (SELECT 1 FROM opslab.users u WHERE u.email = 'seed_manager@test.com')
ON CONFLICT (email) DO NOTHING;

-- 2) 등급 부여. seed_manager 는 NORMAL 로 명시해 둔다(기본값 의존 대신 의도를 남긴다).
UPDATE opslab.users SET seller_tier = 'VIP'       WHERE email = 'seed_seller_vip@test.com';
UPDATE opslab.users SET seller_tier = 'STRATEGIC' WHERE email = 'seed_seller_strategic@test.com';
UPDATE opslab.users SET seller_tier = 'NORMAL'    WHERE email = 'seed_manager@test.com';

-- 3) 상품 재배분 — seed_manager 소유 상품만 대상으로 한다(운영/수동 등록 상품은 건드리지 않는다).
WITH ranked AS (
    SELECT p.id,
           ROW_NUMBER() OVER (ORDER BY p.id) AS rn
      FROM opslab.products p
      JOIN opslab.users u ON u.id = p.seller_id
     WHERE u.email = 'seed_manager@test.com'
)
UPDATE opslab.products p
   SET seller_id = CASE (r.rn % 3)
                       WHEN 1 THEN (SELECT id FROM opslab.users WHERE email = 'seed_seller_vip@test.com')
                       WHEN 2 THEN (SELECT id FROM opslab.users WHERE email = 'seed_seller_strategic@test.com')
                       ELSE p.seller_id   -- 나머지는 seed_manager(NORMAL) 유지
                   END
  FROM ranked r
 WHERE p.id = r.id
   AND (r.rn % 3) IN (1, 2);

-- 4) 등급 생명주기 진입점 기록. V20260808100000 의 백필은 이 마이그레이션 이전에 이미 돌아
--    seed_manager 만 NORMAL 로 넣어 뒀으므로, 새 셀러 2인을 여기서 채운다.
--    이 행이 없으면 승급·강등 평가 배치가 해당 셀러를 아예 보지 않는다.
INSERT INTO opslab.seller_tier_assignment (seller_id, tier, effective_from)
SELECT u.id, u.seller_tier, CURRENT_DATE
  FROM opslab.users u
 WHERE u.email IN ('seed_seller_vip@test.com', 'seed_seller_strategic@test.com')
ON CONFLICT (seller_id) DO UPDATE SET tier = EXCLUDED.tier;

-- 5) 등급 부여 이력. 관리자 화면이 "왜 이 등급인가"를 물을 때 근거가 비어 있으면 안 된다.
INSERT INTO opslab.seller_tier_history (seller_id, prev_tier, new_tier, reason, changed_by, memo)
SELECT u.id, NULL, u.seller_tier, 'ADMIN_OVERRIDE', 'SYSTEM', '시드 데이터 — 등급별 정산 분기 검증용'
  FROM opslab.users u
 WHERE u.email IN ('seed_seller_vip@test.com', 'seed_seller_strategic@test.com')
   AND NOT EXISTS (
        SELECT 1 FROM opslab.seller_tier_history h
         WHERE h.seller_id = u.id AND h.changed_by = 'SYSTEM'
   );
