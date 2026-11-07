package com.flashsale.reaper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.outbox.OutboxEvent;
import com.flashsale.outbox.OutboxEventRepository;
import com.flashsale.seat.Seat;
import com.flashsale.seat.SeatRepository;
import com.flashsale.seat.SeatStatus;
import com.flashsale.seat.dto.SeatUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpirationReaper {

    private static final int BATCH_SIZE = 50;
    public static final String SEAT_CACHE_KEY_PREFIX = "event:";
    public static final String SEAT_CHANNEL_PREFIX = "seat-updates:";

    private final SeatRepository seatRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * FR-2.4: Automated Hold Expiration Reaper daemon.
     * Runs every 5 seconds to reclaim expired seat holds.
     * Uses PostgreSQL 'FOR UPDATE SKIP LOCKED' backed by the idx_seats_reaper partial index.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reclaimExpiredHolds() {
        Instant now = Instant.now();
        List<Seat> expiredSeats = seatRepository.findExpiredHoldsForReclaim(now, BATCH_SIZE);

        if (expiredSeats.isEmpty()) {
            return;
        }

        log.info("HoldExpirationReaper found {} expired seat holds to reclaim", expiredSeats.size());

        // Group expired seats by event ID for efficient batch updates & notifications
        Map<UUID, List<Seat>> seatsByEvent = expiredSeats.stream()
                .collect(Collectors.groupingBy(s -> s.getEvent().getId()));

        for (Map.Entry<UUID, List<Seat>> entry : seatsByEvent.entrySet()) {
            UUID eventId = entry.getKey();
            List<Seat> seats = entry.getValue();
            List<UUID> seatIds = seats.stream().map(Seat::getId).toList();

            for (Seat seat : seats) {
                seat.setStatus(SeatStatus.AVAILABLE);
                seat.setHeldByUserId(null);
                seat.setHoldToken(null);
                seat.setHoldExpiresAt(null);
            }

            seatRepository.saveAll(seats);

            // Emit SEATS_RELEASED event into outbox_events
            try {
                SeatUpdateMessage message = new SeatUpdateMessage(eventId, seatIds, SeatStatus.AVAILABLE, null);
                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .id(UUID.randomUUID())
                        .aggregateType("EVENT")
                        .aggregateId(eventId)
                        .eventType("SEATS_RELEASED")
                        .payload(objectMapper.writeValueAsString(message))
                        .processed(false)
                        .createdAt(now)
                        .build();
                outboxEventRepository.save(outboxEvent);

                // Update Redis Cache & Broadcast to SSE cluster
                updateRedisAndBroadcast(eventId, seatIds, message);
            } catch (Exception e) {
                log.error("Failed to emit outbox / broadcast for reclaimed seats: {}", e.getMessage(), e);
            }
        }
    }

    private void updateRedisAndBroadcast(UUID eventId, List<UUID> seatIds, SeatUpdateMessage message) {
        try {
            String hashKey = SEAT_CACHE_KEY_PREFIX + eventId + ":seats";
            for (UUID seatId : seatIds) {
                redisTemplate.opsForHash().put(hashKey, seatId.toString(), SeatStatus.AVAILABLE.name() + ":");
            }
            redisTemplate.convertAndSend(SEAT_CHANNEL_PREFIX + eventId, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("Non-fatal Redis update error in HoldExpirationReaper: {}", e.getMessage());
        }
    }
}
