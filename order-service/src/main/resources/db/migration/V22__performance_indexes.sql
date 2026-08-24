-- =============================================
-- V22: Performance optimization indexes
-- =============================================

-- Orders: user lookup + status filtering
CREATE INDEX IF NOT EXISTS idx_orders_user_id_status
    ON opslab.orders (user_id, status);

-- Orders: product lookup
CREATE INDEX IF NOT EXISTS idx_orders_product_id
    ON opslab.orders (product_id);

-- Payments: order lookup (frequently joined)
CREATE INDEX IF NOT EXISTS idx_payments_order_id
    ON opslab.payments (order_id);

-- Settlements: date range + status queries (batch processing)
CREATE INDEX IF NOT EXISTS idx_settlements_date_status
    ON opslab.settlements (settlement_date, status);

-- Settlements: orderer name search (LIKE queries)
CREATE INDEX IF NOT EXISTS idx_settlements_created_at
    ON opslab.settlements (created_at DESC);

-- Users: email lookup (login)
CREATE INDEX IF NOT EXISTS idx_users_email
    ON opslab.users (email);

-- Products: category filtering
CREATE INDEX IF NOT EXISTS idx_products_category_id
    ON opslab.products (category_id) WHERE category_id IS NOT NULL;

-- Reviews: product lookup
CREATE INDEX IF NOT EXISTS idx_reviews_product_id
    ON opslab.reviews (product_id);

-- Coupons: expiry date filtering
CREATE INDEX IF NOT EXISTS idx_coupons_expires_at
    ON opslab.coupons (expires_at) WHERE is_active = true;
