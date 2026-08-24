-- Settlement 테이블의 amount 컬럼을 payment_amount, commission, net_amount로 분리
-- TDD 구현에 따라 수수료(3%) 계산 로직 반영

-- 1. 새로운 컬럼 추가
ALTER TABLE settlements
    ADD COLUMN payment_amount DECIMAL(10, 2),
    ADD COLUMN commission DECIMAL(10, 2),
    ADD COLUMN net_amount DECIMAL(10, 2);

-- 2. 기존 데이터 마이그레이션 (amount -> payment_amount, commission 계산, net_amount 계산)
UPDATE settlements
SET payment_amount = amount,
    commission = ROUND(amount * 0.03, 2),
    net_amount = ROUND(amount - (amount * 0.03), 2)
WHERE payment_amount IS NULL;

-- 3. NOT NULL 제약 조건 추가
ALTER TABLE settlements
    ALTER COLUMN payment_amount SET NOT NULL,
    ALTER COLUMN commission SET NOT NULL,
    ALTER COLUMN net_amount SET NOT NULL;

-- 4. 기존 amount 컬럼 삭제
ALTER TABLE settlements
    DROP COLUMN amount;
