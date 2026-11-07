package com.flashsale.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CheckoutRequest(
    @NotNull(message = "eventId is required")
    UUID eventId,

    @NotNull(message = "holdToken is required")
    UUID holdToken,

    @NotEmpty(message = "seatIds cannot be empty")
    List<UUID> seatIds,

    @NotNull(message = "paymentMethodToken is required")
    String paymentMethodToken
) {}
