package com.flashsale.seat.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record HoldSeatRequest(
    @NotEmpty(message = "seatIds list cannot be empty")
    @Size(min = 1, max = 4, message = "You can only hold between 1 and 4 seats at a time")
    List<UUID> seatIds
) {}
