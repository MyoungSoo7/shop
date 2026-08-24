-- V17: 테스트 시드 데이터 (사용자 10, 상품 20, 주문+결제+정산 각 1000건)
-- 비밀번호: password123 (BCrypt 해시)

DO $$
DECLARE
    v_admin_id      BIGINT;
    v_user_ids      BIGINT[];
    v_product_ids   BIGINT[];
    v_product_prices DECIMAL[];
    v_user_id       BIGINT;
    v_product_id    BIGINT;
    v_price         DECIMAL(10,2);
    v_order_id      BIGINT;
    v_payment_id    BIGINT;
    v_created_at    TIMESTAMP;
    v_status        VARCHAR(20);
    i               INT;
BEGIN

    -- ─────────────────────────────────────────
    -- 1. 사용자 (10명: admin 1 + user 9)
    -- ─────────────────────────────────────────
    INSERT INTO users (email, password, role, created_at) VALUES
        ('seed_admin@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ADMIN',   NOW() - INTERVAL '180 days'),
        ('seed_manager@test.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'MANAGER', NOW() - INTERVAL '175 days'),
        ('seed_user1@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '170 days'),
        ('seed_user2@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '160 days'),
        ('seed_user3@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '150 days'),
        ('seed_user4@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '140 days'),
        ('seed_user5@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '130 days'),
        ('seed_user6@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '120 days'),
        ('seed_user7@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '110 days'),
        ('seed_user8@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '100 days'),
        ('seed_user9@test.com',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER',    NOW() - INTERVAL '90 days')
    ON CONFLICT (email) DO UPDATE SET 
        role = EXCLUDED.role,
        password = EXCLUDED.password;

    SELECT id INTO v_admin_id FROM users WHERE email = 'seed_admin@test.com';
    SELECT ARRAY(SELECT id FROM users WHERE email LIKE 'seed_user%@test.com' ORDER BY id)
        INTO v_user_ids;

    -- ─────────────────────────────────────────
    -- 2. 상품 (20개)
    -- ─────────────────────────────────────────
    INSERT INTO products (name, description, price, stock_quantity, status) VALUES
        ('애플 맥북 프로 14인치',    'M3 Pro 칩 탑재 프리미엄 노트북',          2990000.00, 50,  'ACTIVE'),
        ('삼성 갤럭시 S25 울트라',   '최신 플래그십 안드로이드 스마트폰',        1890000.00, 100, 'ACTIVE'),
        ('소니 WH-1000XM6',          '업계 최고 노이즈 캔슬링 헤드폰',           449000.00,  80,  'ACTIVE'),
        ('LG 그램 16',               '초경량 고성능 윈도우 노트북',              1690000.00, 30,  'ACTIVE'),
        ('아이패드 프로 13인치',      'M4 칩 탑재 전문가용 태블릿',              1699000.00, 60,  'ACTIVE'),
        ('나이키 에어맥스 270',       '쿠셔닝 캐주얼 스니커즈',                   179000.00, 200, 'ACTIVE'),
        ('다이슨 에어랩 스타일러',    '올인원 헤어 스타일링 도구',                689000.00,  40,  'ACTIVE'),
        ('닌텐도 스위치 OLED',        '휴대용 하이브리드 게임 콘솔',              399000.00, 120, 'ACTIVE'),
        ('스타벅스 텀블러 세트',      '보온 보냉 스테인리스 텀블러 2종',           49900.00, 300, 'ACTIVE'),
        ('레고 테크닉 포르쉐 911',    '성인 수집용 레고 세트',                    329000.00,  75,  'ACTIVE'),
        ('아이폰 16 Pro 256GB',       '최신 애플 스마트폰 프로 모델',             1550000.00, 150, 'ACTIVE'),
        ('갤럭시 탭 S10 울트라',      '삼성 프리미엄 안드로이드 태블릿',         1290000.00,  45,  'ACTIVE'),
        ('플레이스테이션 5 슬림',     '소니 차세대 게임 콘솔',                    629000.00,  90,  'ACTIVE'),
        ('에어팟 프로 3세대',         '애플 노이즈 캔슬링 무선 이어폰',           389000.00, 200, 'ACTIVE'),
        ('캐논 EOS R50 바디',         '미러리스 디지털 카메라',                   890000.00,  35,  'ACTIVE'),
        ('발뮤다 더 토스터',          '스팀 방식 프리미엄 토스터',                399000.00,  60,  'ACTIVE'),
        ('다이슨 V15 디텍트',         '레이저 감지 무선 청소기',                  899000.00,  55,  'ACTIVE'),
        ('LG 스탠바이미 Go',          '27인치 무선 포터블 스크린',                799000.00,  40,  'ACTIVE'),
        ('브레빌 바리스타 익스프레스','반자동 에스프레소 머신',                   990000.00,  25,  'ACTIVE'),
        ('쿠쿠 압력밥솥 10인용',      '프리미엄 IH 압력밥솥',                    299000.00, 180, 'ACTIVE')
    ON CONFLICT (name) DO NOTHING;

    SELECT ARRAY(SELECT id    FROM products ORDER BY id) INTO v_product_ids;
    SELECT ARRAY(SELECT price FROM products ORDER BY id) INTO v_product_prices;

    -- ─────────────────────────────────────────
    -- 3. 주문 + 결제 + 정산 (각 1000건)
    --    날짜 범위: 최근 90일에 균등 분포
    -- ─────────────────────────────────────────
    FOR i IN 1..1000 LOOP

        -- 사용자/상품 라운드-로빈
        v_user_id    := v_user_ids   [((i - 1) % array_length(v_user_ids, 1))    + 1];
        v_product_id := v_product_ids[((i - 1) % array_length(v_product_ids, 1)) + 1];
        v_price      := v_product_prices[((i - 1) % array_length(v_product_prices, 1)) + 1];

        -- 90일(7,776,000초)에 균등 분포
        v_created_at := NOW() - make_interval(secs => (1000 - i) * 7776.0);

        -- ── 주문 ──
        INSERT INTO orders (user_id, product_id, amount, status, created_at, updated_at)
        VALUES (v_user_id, v_product_id, v_price, 'PAID', v_created_at, v_created_at)
        RETURNING id INTO v_order_id;

        -- ── 결제 (CAPTURED) ──
        INSERT INTO payments (
            order_id, amount, refunded_amount, status,
            payment_method, pg_transaction_id,
            captured_at, created_at, updated_at
        ) VALUES (
            v_order_id, v_price, 0.00, 'CAPTURED',
            CASE (i % 4)
                WHEN 0 THEN 'TOSS_PAYMENTS'
                WHEN 1 THEN 'CARD'
                WHEN 2 THEN 'BANK_TRANSFER'
                ELSE        'VIRTUAL_ACCOUNT'
            END,
            'seed_txn_' || LPAD(i::TEXT, 6, '0'),
            v_created_at + INTERVAL '1 minute',
            v_created_at,
            v_created_at + INTERVAL '1 minute'
        )
        RETURNING id INTO v_payment_id;

        -- ── 정산 상태 결정 (새 상태 머신 5가지 순환) ──
        v_status := CASE (i % 5)
            WHEN 0 THEN 'REQUESTED'
            WHEN 1 THEN 'PROCESSING'
            WHEN 2 THEN 'DONE'
            WHEN 3 THEN 'FAILED'
            ELSE        'CANCELED'
        END;

        -- ── 정산 ──
        INSERT INTO settlements (
            payment_id, order_id,
            payment_amount, commission, net_amount,
            status, settlement_date,
            confirmed_at,
            approved_by, approved_at,
            rejected_by, rejected_at, rejection_reason,
            created_at, updated_at
        ) VALUES (
            v_payment_id,
            v_order_id,
            v_price,
            ROUND(v_price * 0.03, 2),
            ROUND(v_price * 0.97, 2),
            v_status,
            (v_created_at + INTERVAL '1 day')::DATE,
            -- confirmed_at
            CASE WHEN v_status IN ('DONE')
                 THEN v_created_at + INTERVAL '2 days' ELSE NULL END,
            -- approved_by / approved_at
            CASE WHEN v_status IN ('DONE')
                 THEN v_admin_id ELSE NULL END,
            CASE WHEN v_status IN ('DONE')
                 THEN v_created_at + INTERVAL '1 day' ELSE NULL END,
            -- rejected_by / rejected_at / rejection_reason
            CASE WHEN v_status = 'FAILED' THEN v_admin_id ELSE NULL END,
            CASE WHEN v_status = 'FAILED' THEN v_created_at + INTERVAL '1 day' ELSE NULL END,
            CASE WHEN v_status = 'FAILED' THEN '정산 처리 오류: 가맹점 계좌 정보 불일치' ELSE NULL END,
            v_created_at,
            v_created_at + INTERVAL '1 hour'
        );

    END LOOP;

    RAISE NOTICE '시드 데이터 삽입 완료: 사용자 10명, 상품 20개, 주문+결제+정산 각 1000건';

END $$;
