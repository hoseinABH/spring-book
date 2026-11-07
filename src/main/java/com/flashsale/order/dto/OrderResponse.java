package com.flashsale.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID orderId,
    UUID eventId,
    UUID userId,
    BigDecimal totalAmount,
    String status,
    String paymentReference,
    String ticketQrSignature,
    List<UUID> seatIds,
    Instant createdAt
) {}
