package com.flashsale.seat;

import com.flashsale.common.exception.ResourceNotFoundException;
import com.flashsale.event.EventRepository;
import com.flashsale.seat.dto.SeatDto;
import com.flashsale.seat.dto.SeatMapResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatMapService {

    public static final Duration CACHE_TTL = Duration.ofMinutes(15);
    public static final String SEAT_CACHE_KEY_PREFIX = "event:";

    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Hydrates the full venue seat map using the Redis Cache-Aside pattern.
     * 1. Check Redis Hash (event:{eventId}:seats).
     * 2. On Cache Hit: rapidly overlay live status onto seat metadata in < 10ms.
     * 3. On Cache Miss: query PostgreSQL, warm the Redis Hash with TTL, and return.
     */
    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(UUID eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with ID: " + eventId);
        }

        String cacheKey = SEAT_CACHE_KEY_PREFIX + eventId + ":seats";
        Map<Object, Object> cachedEntries = redisTemplate.opsForHash().entries(cacheKey);

        List<Seat> seats = seatRepository.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(eventId);

        if (cachedEntries.isEmpty()) {
            log.info("Redis seat cache miss for event {}. Warming cache from PostgreSQL...", eventId);
            warmRedisCache(cacheKey, seats);
        }

        List<SeatDto> seatDtos = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            SeatStatus status = seat.getStatus();
            Instant holdExpiresAt = seat.getHoldExpiresAt();

            // Overlay latest state from Redis if present
            Object cachedVal = cachedEntries.get(seat.getId().toString());
            if (cachedVal != null) {
                String[] parts = cachedVal.toString().split(":");
                status = SeatStatus.valueOf(parts[0]);
                if (parts.length > 1 && !parts[1].isBlank()) {
                    holdExpiresAt = Instant.parse(parts[1]);
                }
            }

            // Check if hold has naturally expired
            if (status == SeatStatus.HELD && holdExpiresAt != null && holdExpiresAt.isBefore(Instant.now())) {
                status = SeatStatus.AVAILABLE;
                holdExpiresAt = null;
            }

            seatDtos.add(new SeatDto(
                    seat.getId(),
                    seat.getSection(),
                    seat.getRowLabel(),
                    seat.getSeatNumber(),
                    seat.getPrice(),
                    status,
                    holdExpiresAt
            ));
        }

        return new SeatMapResponse(eventId, seatDtos);
    }

    private void warmRedisCache(String cacheKey, List<Seat> seats) {
        try {
            for (Seat seat : seats) {
                String val = seat.getStatus().name() + ":" +
                        (seat.getHoldExpiresAt() != null ? seat.getHoldExpiresAt().toString() : "");
                redisTemplate.opsForHash().put(cacheKey, seat.getId().toString(), val);
            }
            redisTemplate.expire(cacheKey, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to warm Redis cache for {}: {}", cacheKey, e.getMessage());
        }
    }
}
