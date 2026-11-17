CREATE TABLE order_idempotency (
    idempotency_key VARCHAR(128) COLLATE "C" PRIMARY KEY,
    request_payload TEXT NOT NULL,
    order_id BIGINT UNIQUE REFERENCES customer_orders(id) ON DELETE RESTRICT,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT order_idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT order_idempotency_payload_size CHECK (length(request_payload) BETWEEN 1 AND 4096),
    CONSTRAINT order_idempotency_completion_pair CHECK ((order_id IS NULL) = (response_body IS NULL)),
    CONSTRAINT order_idempotency_response_object CHECK (jsonb_typeof(response_body::jsonb) = 'object')
);

-- The claim is incomplete only INSIDE its transaction. Never commit a stranded reservation.
-- Inspect the final row, rather than NEW, because INSERT and completion UPDATE share the transaction.
CREATE FUNCTION ensure_order_idempotency_completed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    incomplete BOOLEAN;
BEGIN
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE idempotency_key = $1 AND order_id IS NULL)',
                   TG_RELID::regclass)
        INTO incomplete USING NEW.idempotency_key;
    IF incomplete THEN
        RAISE EXCEPTION 'Cannot commit an incomplete order idempotency reservation' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER order_idempotency_completed
    AFTER INSERT OR UPDATE ON order_idempotency
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ensure_order_idempotency_completed();
