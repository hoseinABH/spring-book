package com.flashsale.order;

import com.flashsale.common.exception.IdempotencyConflictException;
import com.flashsale.event.Event;
import com.flashsale.event.EventRepository;
import com.flashsale.order.dto.CheckoutRequest;
import com.flashsale.order.dto.OrderResponse;
import com.flashsale.seat.Seat;
import com.flashsale.seat.SeatHoldService;
import com.flashsale.seat.SeatRepository;
import com.flashsale.seat.SeatStatus;
import com.flashsale.seat.dto.HoldSeatRequest;
import com.flashsale.seat.dto.HoldSeatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderCheckoutIdempotencyTest {

    @Autowired
    private OrderCheckoutService orderCheckoutService;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("Idempotency: 10 concurrent requests with the SAME Idempotency-Key -> Exactly 1 order created in DB")
    void testConcurrentCheckoutWithSameIdempotencyKey() throws InterruptedException {
        // 1. Arrange: Create Event, Seat, and acquire initial hold
        UUID testEventId = UUID.randomUUID();
        Event event = Event.builder()
                .id(testEventId)
                .title("Idempotency Test Event")
                .venueName("Grand Arena")
                .totalSeats(1)
                .startsAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .saleStartsAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .createdAt(Instant.now())
                .build();
        eventRepository.save(event);

        UUID testSeatId = UUID.randomUUID();
        Seat seat = Seat.builder()
                .id(testSeatId)
                .event(event)
                .section("VIP")
                .rowLabel("A")
                .seatNumber("1")
                .price(new BigDecimal("150.00"))
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build();
        seatRepository.save(seat);

        UUID userId = UUID.randomUUID();
        HoldSeatResponse holdResponse = seatHoldService.holdSeats(
                testEventId,
                userId,
                new HoldSeatRequest(List.of(testSeatId))
        );

        String sameIdempotencyKey = "idemp-key-" + UUID.randomUUID();
        CheckoutRequest checkoutRequest = new CheckoutRequest(
                testEventId,
                holdResponse.holdToken(),
                List.of(testSeatId),
                "pm_card_visa"
        );

        // 2. Act: Fire 10 concurrent virtual threads with the identical idempotency key
        int concurrency = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(concurrency);

        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger inProgressConflictCount = new AtomicInteger(0);
        List<Exception> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        OrderResponse response = orderCheckoutService.checkout(
                                userId,
                                sameIdempotencyKey,
                                checkoutRequest
                        );
                        if (response != null && "COMPLETED".equals(response.status())) {
                            completedCount.incrementAndGet();
                        }
                    } catch (IdempotencyConflictException e) {
                        inProgressConflictCount.incrementAndGet();
                    } catch (Exception e) {
                        unexpectedExceptions.add(e);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            startGate.countDown();
            finishGate.await();
        }

        // 3. Assert: Exactly 1 order exists in database with this idempotency key
        assertThat(unexpectedExceptions).isEmpty();
        assertThat(completedCount.get() + inProgressConflictCount.get()).isEqualTo(concurrency);

        long ordersInDb = orderRepository.findAll().stream()
                .filter(o -> sameIdempotencyKey.equals(o.getIdempotencyKey()))
                .count();
        assertThat(ordersInDb)
                .as("Strictly ZERO duplicate orders allowed for the same Idempotency-Key")
                .isEqualTo(1);
    }
}
