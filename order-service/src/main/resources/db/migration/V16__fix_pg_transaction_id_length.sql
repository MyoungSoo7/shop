-- Toss paymentKey는 최대 200자 이상일 수 있으므로 컬럼 확장
ALTER TABLE payments ALTER COLUMN pg_transaction_id TYPE VARCHAR(500);