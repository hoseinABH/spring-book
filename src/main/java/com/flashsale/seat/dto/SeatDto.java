package com.flashsale.seat.dto;

import com.flashsale.seat.SeatStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SeatDto(
    UUID id,
    String section,
    String row,
    String number,
    BigDecimal price,
    SeatStatus status,
    Instant holdExpiresAt
) {}
