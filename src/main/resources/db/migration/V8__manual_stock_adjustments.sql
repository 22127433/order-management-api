-- Preserve the four existing movement types and their rules. No history is rewritten.
ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movement_type;
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movement_type CHECK (
    (movement_type = 'INITIAL_STOCK' AND quantity_delta >= 0 AND stock_before = 0
        AND reference_order_id IS NULL AND reason IS NULL)
    OR (movement_type = 'ORDER_DEDUCTION' AND quantity_delta < 0
        AND reference_order_id IS NOT NULL AND reason IS NULL)
    OR (movement_type = 'ORDER_CANCELLATION' AND quantity_delta > 0
        AND reference_order_id IS NOT NULL AND reason IS NULL)
    OR (movement_type = 'MANUAL_RESTOCK' AND quantity_delta > 0
        AND reference_order_id IS NULL AND reason IS NOT NULL AND length(btrim(reason)) > 0)
    OR (movement_type = 'MANUAL_ADJUSTMENT' AND quantity_delta <> 0
        AND reference_order_id IS NULL AND reason IS NOT NULL AND length(btrim(reason)) > 0)
);
