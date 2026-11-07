package com.flashsale.event.dto;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
    UUID id,
    String title,
    String venueName,
    int totalSeats,
    Instant startsAt,
    Instant saleStartsAt,
    Instant createdAt
) {}
