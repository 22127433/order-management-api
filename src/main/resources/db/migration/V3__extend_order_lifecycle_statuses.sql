-- PostgreSQL named the inline V1 status constraint customer_orders_status_check.
-- Keep existing rows and the VARCHAR/EnumType.STRING mapping unchanged.
ALTER TABLE customer_orders DROP CONSTRAINT customer_orders_status_check;

ALTER TABLE customer_orders ADD CONSTRAINT customer_orders_status_check
    CHECK (status IN ('CREATED', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED'));
