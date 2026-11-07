package com.flashsale.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.exception.IdempotencyConflictException;
import com.flashsale.common.exception.InvalidHoldException;
import com.flashsale.common.exception.ResourceNotFoundException;
import com.flashsale.common.exception.SeatNotAvailableException;
import com.flashsale.event.Event;
import com.flashsale.event.EventRepository;
import com.flashsale.order.dto.CheckoutRequest;
import com.flashsale.order.dto.OrderResponse;
import com.flashsale.outbox.OutboxEvent;
import com.flashsale.outbox.OutboxEventRepository;
import com.flashsale.seat.Seat;
import com.flashsale.seat.SeatRepository;
import com.flashsale.seat.SeatStatus;
import com.flashsale.seat.dto.SeatUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCheckoutService {

    public static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    public static final Duration IDEMPOTENCY_TTL = Duration.ofDays(1);
    public static final String SEAT_CACHE_KEY_PREFIX = "event:";
    public static final String SEAT_CHANNEL_PREFIX = "seat-updates:";
    private static final String HMAC_SECRET = "senior-flash-sale-secret-key-32b!";

    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Executes the critical checkout mutation under strict Zero Duplicate Charge and Zero Oversell guarantees.
     *
     * SENIOR ARCHITECTURE PATTERNS APPLIED:
     * 1. Distributed Idempotency Key: Uses Redis SETNX (atomic key reservation) to block concurrent duplicate POSTs.
     * 2. Database Unique Constraint: orders.idempotency_key guarantees duplicate requests cannot both insert.
     * 3. Pessimistic Row Locking: SELECT ... FOR UPDATE on held seats to prevent the Reaper daemon from reclaiming
     *    the seats mid-payment.
     * 4. Hold Integrity Check: Validates held_by_user_id, hold_token, and hold_expires_at > NOW().
     * 5. Transactional Outbox: Persists ORDER_PAID event in the SAME ACID commit as the order and seat status changes.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse checkout(UUID userId, String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidHoldException("Idempotency-Key header is mandatory for checkout operations");
        }

        // 1. Check if Order was already completed with this Idempotency Key
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            log.info("Idempotency key hit: returning previously committed order {}", existingOrder.get().getId());
            return mapToResponse(existingOrder.get());
        }

        // 2. Atomic Redis Distributed Lock on Idempotency Key (SET key "IN_PROGRESS" NX EX 120s)
        String redisIdempKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisIdempKey, "IN_PROGRESS", Duration.ofSeconds(120));
        if (Boolean.FALSE.equals(acquired)) {
            throw new IdempotencyConflictException("An order mutation with this Idempotency-Key is currently in progress. Please retry shortly.");
        }

        try {
            Event event = eventRepository.findById(request.eventId())
                    .orElseThrow(() -> new ResourceNotFoundException("Event not found with ID: " + request.eventId()));

            // 3. Acquire Pessimistic Locks on the held seats in ascending order
            List<UUID> sortedSeatIds = request.seatIds().stream().sorted().toList();
            List<Seat> lockedSeats = seatRepository.findByIdsWithPessimisticLock(sortedSeatIds);

            if (lockedSeats.size() != sortedSeatIds.size()) {
                throw new SeatNotAvailableException("One or more requested seats could not be found");
            }

            // 4. Verify Hold Ownership, Token Match, and Non-Expired TTL
            Instant now = Instant.now();
            BigDecimal totalAmount = BigDecimal.ZERO;

            for (Seat seat : lockedSeats) {
                if (seat.getStatus() != SeatStatus.HELD) {
                    throw new SeatNotAvailableException("Seat " + seat.getId() + " is not in HELD status (current: " + seat.getStatus() + ")");
                }
                if (!userId.equals(seat.getHeldByUserId())) {
                    throw new InvalidHoldException("Seat " + seat.getId() + " is held by another user");
                }
                if (!request.holdToken().equals(seat.getHoldToken())) {
                    throw new InvalidHoldException("Hold token mismatch for seat " + seat.getId());
                }
                if (seat.getHoldExpiresAt() == null || seat.getHoldExpiresAt().isBefore(now)) {
                    throw new InvalidHoldException("Hold reservation for seat " + seat.getId() + " has expired");
                }
                totalAmount = totalAmount.add(seat.getPrice());
            }

            // 5. Invoke Resilient Outbound Payment Gateway
            UUID orderId = UUID.randomUUID();
            String paymentReference = paymentGatewayClient.processPayment(orderId, totalAmount, request.paymentMethodToken());

            // 6. Generate Cryptographic Ticket QR Signature (HMAC-SHA256)
            String qrSignature = generateQrSignature(orderId, userId, sortedSeatIds);

            // 7. Transition Seats to SOLD & build Order entity
            Order order = Order.builder()
                    .id(orderId)
                    .userId(userId)
                    .event(event)
                    .totalAmount(totalAmount)
                    .status(OrderStatus.COMPLETED)
                    .idempotencyKey(idempotencyKey)
                    .paymentReference(paymentReference)
                    .ticketQrSignature(qrSignature)
                    .createdAt(now)
                    .build();

            for (Seat seat : lockedSeats) {
                seat.setStatus(SeatStatus.SOLD);
                seat.setHeldByUserId(null);
                seat.setHoldToken(null);
                seat.setHoldExpiresAt(null);

                OrderItem item = OrderItem.builder()
                        .id(UUID.randomUUID())
                        .order(order)
                        .seat(seat)
                        .price(seat.getPrice())
                        .build();
                order.addItem(item);
            }

            seatRepository.saveAll(lockedSeats);
            Order savedOrder = orderRepository.save(order);

            // 8. Transactional Outbox: Persist ORDER_PAID event in the exact same commit
            emitOrderPaidOutboxEvent(savedOrder);

            // 9. Update Redis Seat Map & Broadcast to SSE Cluster
            broadcastSeatSold(request.eventId(), lockedSeats);

            // 10. Update Redis Idempotency status to COMPLETED
            redisTemplate.opsForValue().set(redisIdempKey, "COMPLETED", IDEMPOTENCY_TTL);

            return mapToResponse(savedOrder);
        } catch (Exception e) {
            // If payment failed or error occurred before commit, remove the in-progress idempotency key so user can retry
            redisTemplate.delete(redisIdempKey);
            throw e;
        }
    }

    private void emitOrderPaidOutboxEvent(Order order) {
        try {
            OrderResponse response = mapToResponse(order);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("ORDER")
                    .aggregateId(order.getId())
                    .eventType("ORDER_PAID")
                    .payload(objectMapper.writeValueAsString(response))
                    .processed(false)
                    .createdAt(Instant.now())
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order paid outbox payload", e);
            throw new RuntimeException("Outbox serialization failure", e);
        }
    }

    private void broadcastSeatSold(UUID eventId, List<Seat> soldSeats) {
        try {
            String hashKey = SEAT_CACHE_KEY_PREFIX + eventId + ":seats";
            List<UUID> seatIds = soldSeats.stream().map(Seat::getId).toList();

            for (Seat seat : soldSeats) {
                redisTemplate.opsForHash().put(hashKey, seat.getId().toString(), SeatStatus.SOLD.name() + ":");
            }

            SeatUpdateMessage message = new SeatUpdateMessage(eventId, seatIds, SeatStatus.SOLD, null);
            redisTemplate.convertAndSend(SEAT_CHANNEL_PREFIX + eventId, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("Non-fatal Redis update/publish error during checkout: {}", e.getMessage());
        }
    }

    private String generateQrSignature(UUID orderId, UUID userId, List<UUID> seatIds) {
        try {
            String raw = orderId + ":" + userId + ":" + seatIds;
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] signed = sha256Hmac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ticket QR signature", e);
        }
    }

    public OrderResponse mapToResponse(Order order) {
        List<UUID> seatIds = order.getItems().stream()
                .map(item -> item.getSeat().getId())
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getEvent().getId(),
                order.getUserId(),
                order.getTotalAmount(),
                order.getStatus().name(),
                order.getPaymentReference(),
                order.getTicketQrSignature(),
                seatIds,
                order.getCreatedAt()
        );
    }
}
