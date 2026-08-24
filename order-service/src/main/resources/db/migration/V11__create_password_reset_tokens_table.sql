-- V11: Create password_reset_tokens table
CREATE TABLE password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    expiry_date TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_password_reset_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Create indexes for better query performance
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_tokens_token ON password_reset_tokens(token);
CREATE INDEX idx_password_reset_tokens_expiry_date ON password_reset_tokens(expiry_date);

-- Add comment
COMMENT ON TABLE password_reset_tokens IS '비밀번호 재설정 토큰 테이블';
COMMENT ON COLUMN password_reset_tokens.id IS '토큰 ID';
COMMENT ON COLUMN password_reset_tokens.user_id IS '사용자 ID';
COMMENT ON COLUMN password_reset_tokens.token IS '재설정 토큰 (UUID)';
COMMENT ON COLUMN password_reset_tokens.expiry_date IS '만료 일시';
COMMENT ON COLUMN password_reset_tokens.used IS '사용 여부';
COMMENT ON COLUMN password_reset_tokens.created_at IS '생성 일시';
