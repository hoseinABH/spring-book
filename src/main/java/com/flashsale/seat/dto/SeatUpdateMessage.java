package com.flashsale.seat.dto;

import com.flashsale.seat.SeatStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SeatUpdateMessage(
    UUID eventId,
    List<UUID> seatIds,
    SeatStatus status,
    Instant holdExpiresAt
) {}
