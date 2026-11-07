package com.flashsale.seat.dto;

import java.util.List;
import java.util.UUID;

public record SeatMapResponse(
    UUID eventId,
    List<SeatDto> seats
) {}
