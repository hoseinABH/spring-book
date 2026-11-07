-- 1. Events Table
CREATE TABLE events (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    venue_name VARCHAR(255) NOT NULL,
    total_seats INT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    sale_starts_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Seats Table (Core High-Contention Table)
CREATE TABLE seats (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    section VARCHAR(32) NOT NULL,
    row_label VARCHAR(16) NOT NULL,
    seat_number VARCHAR(16) NOT NULL,
    price NUMERIC(12, 2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, HELD, SOLD
    held_by_user_id UUID,
    hold_token UUID,
    hold_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (event_id, section, row_label, seat_number)
);

-- Partial index so the Reaper worker scans ONLY active holds in <1ms without scanning millions of sold/available seats
CREATE INDEX idx_seats_reaper ON seats (hold_expires_at)
WHERE status = 'HELD';

-- 3. Orders Table
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    event_id UUID NOT NULL REFERENCES events(id),
    total_amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- COMPLETED, REFUNDED
    idempotency_key VARCHAR(128) UNIQUE NOT NULL,
    payment_reference VARCHAR(128) NOT NULL,
    ticket_qr_signature VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Order Line Items (Supports multi-seat holds up to 4 seats)
CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    seat_id UUID UNIQUE NOT NULL REFERENCES seats(id),
    price NUMERIC(12, 2) NOT NULL
);

-- 5. Transactional Outbox Table
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL, -- 'EVENT', 'ORDER'
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,     -- 'SEATS_HELD', 'SEATS_RELEASED', 'ORDER_PAID'
    payload JSONB NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Partial index for high-throughput outbox dispatching
CREATE INDEX idx_outbox_unprocessed ON outbox_events (created_at)
WHERE processed = FALSE;
