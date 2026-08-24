-- Add updated_at column to users table (required by clean architecture User domain)
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT NOW();
