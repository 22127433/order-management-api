-- Range filtering and deterministic price-ascending pagination share this B-tree.
CREATE INDEX idx_products_price_id ON products(price, id);
