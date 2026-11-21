-- Preserve products and history; all existing rows and omitted future values are active.
ALTER TABLE products ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
