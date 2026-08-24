-- V10: Add failure_reason column to settlements table
-- Domain model stores failure reasons via Settlement.fail(reason) but JPA entity was missing the column
ALTER TABLE settlements ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500);
