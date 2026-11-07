package com.flashsale.seat;

import com.flashsale.seat.dto.HoldSeatRequest;
import com.flashsale.seat.dto.HoldSeatResponse;
import com.flashsale.seat.dto.SeatMapResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}")
@RequiredArgsConstructor
public class SeatController {

    private final SeatMapService seatMapService;
    private final SeatHoldService seatHoldService;
    private final SeatStreamService seatStreamService;

    /**
     * FR-1.2: Seat Map Hydration (<30ms via Redis Cache-Aside)
     */
    @GetMapping("/seats")
    public ResponseEntity<SeatMapResponse> getSeatMap(@PathVariable UUID eventId) {
        return ResponseEntity.ok(seatMapService.getSeatMap(eventId));
    }

    /**
     * FR-1.3: Live Seat State Broadcast via Server-Sent Events (SSE)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSeatUpdates(@PathVariable UUID eventId) {
        return seatStreamService.subscribe(eventId);
    }

    /**
     * FR-2.1 & FR-2.2: Atomic Seat Reservation with Deterministic Lock Ordering
     */
    @PostMapping("/seats/hold")
    public ResponseEntity<HoldSeatResponse> holdSeats(
            @PathVariable UUID eventId,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @Valid @RequestBody HoldSeatRequest request
    ) {
        UUID userId = (headerUserId != null) ? headerUserId : UUID.fromString("00000000-0000-0000-0000-000000000001");
        HoldSeatResponse response = seatHoldService.holdSeats(eventId, userId, request);
        return ResponseEntity.ok(response);
    }
}
