# Product Requirements Document (PRD): High-Concurrency Event Ticketing & Flash-Sale Engine

**Document Version:** 1.0  
**Status:** Ready for Implementation  
**Target Stack:** Java 21, Spring Boot 3.4+, PostgreSQL 16, Redis 7, React/Next.js or Angular 19  
**Architecture Style:** Modular Monolith (Package-by-Feature) with Event-Driven Transactional Outbox

---

## 1. Executive Summary & Problem Statement

Standard CRUD applications assume low contention on individual database rows. In an event ticketing or flash-sale platform, thousands of concurrent users attempt to reserve and purchase the exact same finite inventory (specific venue seats) within milliseconds of an event going live.

This system is engineered to solve the four core failure modes of high-traffic ticketing and financial platforms:
1. **Double-Booking (Race Conditions):** Multiple users simultaneously reserving the same seat due to read-modify-write race conditions.
2. **Abandoned Cart Inventory Lockup:** Users holding seats and closing their browsers, starving legitimate buyers of inventory.
3. **Duplicate Charges (Non-Idempotent Retries):** Flaky mobile networks or client retries causing duplicate `POST /checkout` mutations.
4. **Thundering Herd Database Collapse:** Thousands of connected browsers polling the database every second to check seat availability.

---

## 2. Target Audience & User Personas

* **The Flash-Sale Buyer (End User):** Joins the event page prior to the drop, views real-time seat availability on an interactive venue map, locks up to 4 seats for a strict 10-minute checkout window, and completes payment securely.
* **The Venue / Event Organizer (Backoffice Admin):** Creates events, configures venue seating tiers and pricing, monitors live sales velocity, and inspects transaction/audit logs.
* **The Platform Automated Reaper (System Actor):** Background daemon that reclaims expired seat holds and relays transactional outbox events to downstream notification and PDF worker queues.

---

## 3. Functional Requirements & User Stories

### Epic 1: Event Discovery & Real-Time Seat Map
* **FR-1.1 (Event Catalog):** Users can browse upcoming events with keyset-paginated listings and cached event metadata.
* **FR-1.2 (Seat Map Hydration):** Loading an event page fetches the current state (`AVAILABLE`, `HELD`, `SOLD`) of all seats in the venue in under 30ms via Redis Cache-Aside.
* **FR-1.3 (Live Seat State Broadcast):** When any seat changes status, all connected clients viewing that event receive a real-time delta payload (`{ seatIds, status, holdExpiresAt }`) within 200ms via **Server-Sent Events (SSE)** backed by Redis Pub/Sub without polling the database.

### Epic 2: High-Concurrency Temporary Seat Hold (The Core Contention Path)
* **FR-2.1 (Atomic Seat Reservation):** A user can request to hold 1 to 4 specific seats. If *any* requested seat is already `HELD` or `SOLD`, the entire request fails atomically (`409 Conflict`).
* **FR-2.2 (Deterministic Lock Ordering):** When locking multiple seats in a single transaction, the backend must sort `seat_id` UUIDs lexicographically before executing `SELECT ... FOR UPDATE` to guarantee zero database deadlocks when two users select overlapping seat sets in reverse order.
* **FR-2.3 (10-Minute TTL Enforcement):** Every successful hold sets `status = 'HELD'`, records `held_by_user_id` and a unique `hold_token`, and sets `hold_expires_at = NOW() + 10 minutes`.
* **FR-2.4 (Automated Hold Expiration Reaper):** A scheduled background job runs every 5 seconds using `SELECT ... FOR UPDATE SKIP LOCKED` to transition expired `HELD` seats back to `AVAILABLE`, update Redis cache, and broadcast `SEATS_RELEASED` via SSE.

### Epic 3: Idempotent Checkout & Resilient Payment Processing
* **FR-3.1 (Idempotency Key Enforcement):** All `POST /api/v1/orders/checkout` requests require an `Idempotency-Key` UUID header. Concurrent duplicate requests return `409 Conflict` (`IN_PROGRESS`), and completed duplicate requests return the cached `200 OK` order response without re-invoking the payment gateway.
* **FR-3.2 (Hold Ownership & Expiry Verification):** Before charging, the checkout transaction locks the seats and verifies that they are still `HELD` by the requesting `userId`, match the `hold_token`, and satisfy `hold_expires_at > NOW()`.
* **FR-3.3 (Payment Gateway Circuit Breaking):** Outbound payment gateway calls enforce a hard 3-second timeout and a `Resilience4j` Circuit Breaker. If the payment gateway fails or times out, the seat hold remains intact (until its natural TTL) so the user can retry payment safely.

### Epic 4: Transactional Outbox & Post-Purchase Fulfillment
* **FR-4.1 (Zero-Loss Event Emission):** Upon successful payment, the database transaction updates the seats to `SOLD`, inserts the `orders` and `order_items` records, and inserts an `ORDER_PAID` event into `outbox_events` in the **exact same ACID commit**.
* **FR-4.2 (Async Ticket & QR Generation):** An outbox relay worker picks up unprocessed `ORDER_PAID` events and asynchronously generates a cryptographically signed QR code ticket (HMAC-SHA256 of `orderId + seatId + userId`) and dispatches a confirmation notification.

---

## 4. Non-Functional Requirements (SLAs & SLOs)

| Metric | Target SLA | Architectural Mechanism |
|---|---|---|
| **Seat Map Read Latency (p99)** | `< 25ms` | Redis Hash (`HGETALL event:{id}:seats`) + local cache |
| **Seat Hold Write Latency (p95)** | `< 80ms` | Java 21 Virtual Threads + Indexed `SELECT ... FOR UPDATE` |
| **Oversell / Double-Booking Rate** | **`0.00%` (Strict Zero)** | PostgreSQL Row-Level Locking + Optimistic `@Version` column |
| **Duplicate Payment Rate** | **`0.00%` (Strict Zero)** | Atomic Redis `SETNX` Idempotency Filter + Unique DB constraint |
| **Throughput Under Flash Sale** | `2,500+ req/sec` (Read) / `500+ req/sec` (Write) | Distributed Redis Lua Rate Limiter + Virtual Threads |

---

## 5. System Architecture & State Machine

### Seat Lifecycle State Machine
* **`AVAILABLE` $\rightarrow$ `HELD`:** Triggered by `POST /seats/hold`. Requires row lock and `status == 'AVAILABLE'`.
* **`HELD` $\rightarrow$ `SOLD`:** Triggered by `POST /orders/checkout`. Requires `status == 'HELD'`, `held_by_user_id == currentUserId`, `hold_token == requestHoldToken`, and `hold_expires_at > NOW()`.
* **`HELD` $\rightarrow$ `AVAILABLE`:** Triggered by explicit user cancellation (`DELETE /seats/hold`) OR the background Reaper job when `hold_expires_at <= NOW()`.
* **`SOLD` $\rightarrow$ `AVAILABLE`:** Triggered only by an authenticated Admin refund workflow.

### End-to-End Component Topology
```text
[Client UI (React / Next.js / Angular)]
   │  ▲
   │  └── 4. Live Seat Updates via Server-Sent Events (SSE)
   ▼
[Spring Boot 3 API (Java 21 Virtual Threads)]
   ├──► [Redis 7]
   │      ├─ Distributed Rate Limiter (Atomic Lua Script)
   │      ├─ Idempotency Lock (SET key "IN_PROGRESS" NX EX 86400)
   │      ├─ Seat Map Cache (Hash: event:{id}:seats)
   │      └─ Pub/Sub Channel (topic: seat-updates:{eventId})
   │
   └──► [PostgreSQL 16 (Primary System of Record)]
          ├─ ACID Transaction (seats + orders + outbox_events)
          └─ Background Outbox Poller & Hold Reaper (FOR UPDATE SKIP LOCKED)
```

---

## 6. Database Schema Specification (Flyway `V1__init_schema.sql`)

```sql
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
    event_id UUID NOT NULL REFERENCES events(id),
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

-- Partial index so the Reaper worker scans ONLY active holds in <1ms
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
    order_id UUID NOT NULL REFERENCES orders(id),
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

CREATE INDEX idx_outbox_unprocessed ON outbox_events (created_at)
WHERE processed = FALSE;
```

---

## 7. Core API Contract Specification

### 7.1 `GET /api/v1/events/{eventId}/seats`
Returns the full seat map for initial UI hydration.
* **Response `200 OK`:**
```json
{
  "eventId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "seats": [
    {
      "id": "1a2b3c4d-0001-0000-0000-000000000001",
      "section": "VIP",
      "row": "A",
      "number": "12",
      "price": 150.00,
      "status": "HELD",
      "holdExpiresAt": "2026-11-07T18:15:00Z"
    }
  ]
}
```

### 7.2 `GET /api/v1/events/{eventId}/stream` (Server-Sent Events)
Produces `text/event-stream` pushing real-time seat transitions to connected browsers.
* **Event Payload (`event: seat-update`):**
```json
{
  "seatIds": ["1a2b3c4d-0001-0000-0000-000000000001"],
  "status": "HELD",
  "holdExpiresAt": "2026-11-07T18:15:00Z"
}
```

### 7.3 `POST /api/v1/events/{eventId}/seats/hold`
Atomically locks 1–4 seats for 10 minutes.
* **Headers:** `Authorization: Bearer <JWT>`
* **Request Body:**
```json
{
  "seatIds": [
    "1a2b3c4d-0001-0000-0000-000000000002",
    "1a2b3c4d-0001-0000-0000-000000000001"
  ]
}
```
* **Success Response `200 OK`:**
```json
{
  "holdToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "seatIds": [
    "1a2b3c4d-0001-0000-0000-000000000001",
    "1a2b3c4d-0001-0000-0000-000000000002"
  ],
  "expiresAt": "2026-11-07T18:15:00Z"
}
```
* **Error Responses:**
  * `409 Conflict` (RFC 7807 `ProblemDetail`): One or more requested seats are no longer `AVAILABLE`.
  * `422 Unprocessable Entity`: User requested more than 4 seats or already holds maximum allowed seats for this event.

### 7.4 `POST /api/v1/orders/checkout`
Converts a valid seat hold into a paid order.
* **Headers:**
  * `Authorization: Bearer <JWT>`
  * `Idempotency-Key: <UUID-v4>` (Mandatory)
* **Request Body:**
```json
{
  "eventId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "holdToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "seatIds": [
    "1a2b3c4d-0001-0000-0000-000000000001",
    "1a2b3c4d-0001-0000-0000-000000000002"
  ],
  "paymentMethodToken": "pm_card_visa"
}
```

---

## 8. Edge Cases & Failure Modes

1. **Deadlock on Multi-Seat Hold:**
   * *Scenario:* User A requests `hold([Seat_1, Seat_2])` at the exact same millisecond User B requests `hold([Seat_2, Seat_1])`.
   * *Resolution:* The service sorts `seatIds` in ascending order (`seatIds.stream().sorted().toList()`) before executing `SELECT ... FROM seats WHERE id IN (:ids) ORDER BY id ASC FOR UPDATE`. Both transactions attempt to lock `Seat_1` first; one wins, the other waits and immediately receives `409 Conflict`.
2. **Hold Expires While Payment Gateway is In-Flight:**
   * *Scenario:* User clicks "Pay" with 1 second left on their 10-minute timer, and the payment gateway takes 2 seconds to respond.
   * *Resolution:* Acquire the pessimistic row lock on the `seats` rows *before* initiating the payment capture (or extend `hold_expires_at` by 30 seconds upon entering checkout) so the Reaper job (`SKIP LOCKED`) cannot reclaim the seat while payment is in flight.
3. **Multi-Pod SSE Broadcasting:**
   * *Scenario:* User A is connected to Backend Pod 1's SSE stream, while User B sends a `POST /seats/hold` request routed to Backend Pod 2.
   * *Resolution:* Pod 2 publishes the seat state change to a **Redis Pub/Sub topic** (`seat-updates:{eventId}`). All backend pods subscribe to Redis Pub/Sub and fan the message out to their local in-memory `SseEmitter` connections.

---

## 9. Implementation Milestones & Verification Suite

### Milestone 1: Core Schema, Locking & Concurrency Verification
* Configure Spring Boot 3.4 (Java 21 Virtual Threads), Flyway, PostgreSQL, and Testcontainers.
* Implement `SeatHoldService` with deterministic lock ordering and `PESSIMISTIC_WRITE`.
* **Verification:** JUnit 5 + Testcontainers test spawning 100 concurrent Virtual Threads attempting to reserve the same seat; assert `1` success (`200 OK`) and `99` conflicts (`409 Conflict`).

### Milestone 2: Redis Cache-Aside, Idempotency & Rate Limiting
* Implement Redis seat map caching, Lua-based distributed rate limiting, and `@Idempotent` checkout protection.
* **Verification:** Concurrently fire 10 identical `POST /orders/checkout` requests with the same `Idempotency-Key`; assert payment gateway mock is called **exactly once**.

### Milestone 3: Transactional Outbox, Reaper Worker & Live SSE
* Implement `HoldExpirationReaper` and `OutboxRelayWorker` using `FOR UPDATE SKIP LOCKED`, connected to Redis Pub/Sub and `SseEmitter`.

### Milestone 4: Frontend Integration & Resilience
* Build the interactive venue seat map UI with live SSE updates, optimistic hold timers, and `Resilience4j` circuit breaking on the checkout flow.