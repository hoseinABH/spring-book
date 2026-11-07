package com.flashsale.order;

import com.flashsale.order.dto.CheckoutRequest;
import com.flashsale.order.dto.OrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCheckoutService orderCheckoutService;

    /**
     * FR-3.1: Idempotent Checkout & Resilient Payment Processing
     */
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @Valid @RequestBody CheckoutRequest request
    ) {
        UUID userId = (headerUserId != null) ? headerUserId : UUID.fromString("00000000-0000-0000-0000-000000000001");
        OrderResponse response = orderCheckoutService.checkout(userId, idempotencyKey, request);
        return ResponseEntity.ok(response);
    }
}
