-- orders 테이블에 product_id 컬럼 추가
ALTER TABLE orders ADD COLUMN product_id BIGINT;

ALTER TABLE orders
    ADD CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES products(id);

CREATE INDEX idx_orders_product_id ON orders(product_id);