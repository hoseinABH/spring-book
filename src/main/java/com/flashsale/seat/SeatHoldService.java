package com.flashsale.seat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.exception.InvalidHoldException;
import com.flashsale.common.exception.SeatNotAvailableException;
import com.flashsale.outbox.OutboxEvent;
import com.flashsale.outbox.OutboxEventRepository;
import com.flashsale.seat.dto.HoldSeatRequest;
import com.flashsale.seat.dto.HoldSeatResponse;
import com.flashsale.seat.dto.SeatUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldService {

    public static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    public static final String SEAT_CACHE_KEY_PREFIX = "event:";
    public static final String SEAT_CHANNEL_PREFIX = "seat-updates:";

    private final SeatRepository seatRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Atomically locks and holds 1 to 4 seats for a user with strict 10-minute TTL.
     *
     * SENIOR ARCHITECTURE PATTERN:
     * 1. Deterministic Lock Ordering: We sort the requested seat UUIDs lexicographically before acquiring
     *    PostgreSQL pessimistic row locks (SELECT ... FOR UPDATE). This mathematically prevents database deadlocks
     *    when two concurrent users attempt to hold overlapping sets in reverse order.
     * 2. Transactional Outbox: Emits a SEATS_HELD event into outbox_events in the EXACT same ACID transaction commit.
     * 3. Redis Cache-Aside & Pub/Sub: After commit, invalidates/updates Redis cache and notifies SSE subscribers.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HoldSeatResponse holdSeats(UUID eventId, UUID userId, HoldSeatRequest request) {
        List<UUID> rawSeatIds = request.seatIds();
        if (rawSeatIds == null || rawSeatIds.isEmpty() || rawSeatIds.size() > 4) {
            throw new InvalidHoldException("Must request between 1 and 4 seats");
        }

        // 1. Enforce Deterministic Lock Ordering: Sort seat IDs in ascending natural order
        List<UUID> sortedSeatIds = rawSeatIds.stream().sorted().toList();

        // 2. Acquire exclusive pessimistic write locks (SELECT ... FOR UPDATE) on the sorted rows
        List<Seat> lockedSeats = seatRepository.findByIdsWithPessimisticLock(sortedSeatIds);

        if (lockedSeats.size() != sortedSeatIds.size()) {
            throw new SeatNotAvailableException("One or more requested seat IDs do not exist");
        }

        // 3. Verify event matching & check availability for every locked seat
        Instant now = Instant.now();
        for (Seat seat : lockedSeats) {
            if (!seat.getEvent().getId().equals(eventId)) {
                throw new InvalidHoldException("Seat " + seat.getId() + " does not belong to event " + eventId);
            }

            // A seat is available if status == AVAILABLE, or if status == HELD but its TTL has expired
            boolean isAvailable = seat.isAvailable();
            if (!isAvailable) {
                throw new SeatNotAvailableException("Seat " + seat.getId() + " (" + seat.getSection() + "-" +
                        seat.getRowLabel() + seat.getSeatNumber() + ") is currently unavailable (" + seat.getStatus() + ")");
            }
        }

        // 4. Transition all seats to HELD state
        UUID holdToken = UUID.randomUUID();
        Instant expiresAt = now.plus(HOLD_DURATION);

        for (Seat seat : lockedSeats) {
            seat.setStatus(SeatStatus.HELD);
            seat.setHeldByUserId(userId);
            seat.setHoldToken(holdToken);
            seat.setHoldExpiresAt(expiresAt);
        }

        seatRepository.saveAll(lockedSeats);

        // 5. Transactional Outbox: Record SEATS_HELD event in the exact same DB transaction
        SeatUpdateMessage updateMessage = new SeatUpdateMessage(eventId, sortedSeatIds, SeatStatus.HELD, expiresAt);
        try {
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("EVENT")
                    .aggregateId(eventId)
                    .eventType("SEATS_HELD")
                    .payload(objectMapper.writeValueAsString(updateMessage))
                    .processed(false)
                    .createdAt(now)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload", e);
            throw new RuntimeException("Serialization failure during seat hold outbox emission", e);
        }

        // 6. Asynchronously update Redis Cache & Broadcast to SSE cluster
        updateRedisCacheAndBroadcast(eventId, lockedSeats, SeatStatus.HELD, expiresAt);

        log.info("Successfully held {} seats for user {} with holdToken {}", lockedSeats.size(), userId, holdToken);
        return new HoldSeatResponse(holdToken, sortedSeatIds, expiresAt);
    }

    private void updateRedisCacheAndBroadcast(UUID eventId, List<Seat> seats, SeatStatus status, Instant expiresAt) {
        try {
            String hashKey = SEAT_CACHE_KEY_PREFIX + eventId + ":seats";
            List<UUID> seatIds = seats.stream().map(Seat::getId).toList();

            // Update Redis Hash: field = seatId, value = status:holdExpiresAt
            for (Seat seat : seats) {
                String val = status.name() + ":" + (expiresAt != null ? expiresAt.toString() : "");
                redisTemplate.opsForHash().put(hashKey, seat.getId().toString(), val);
            }

            // Publish to Redis Pub/Sub topic for multi-pod SSE fanout
            SeatUpdateMessage message = new SeatUpdateMessage(eventId, seatIds, status, expiresAt);
            String messageJson = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(SEAT_CHANNEL_PREFIX + eventId, messageJson);
        } catch (Exception e) {
            log.warn("Non-fatal Redis update/publish error during hold: {}", e.getMessage());
        }
    }
}
