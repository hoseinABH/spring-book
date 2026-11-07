package com.flashsale.seat.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HoldSeatResponse(
    UUID holdToken,
    List<UUID> seatIds,
    Instant expiresAt
) {}
