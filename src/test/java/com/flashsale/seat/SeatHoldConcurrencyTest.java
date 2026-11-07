package com.flashsale.seat;

import com.flashsale.common.exception.SeatNotAvailableException;
import com.flashsale.event.Event;
import com.flashsale.event.EventRepository;
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
class SeatHoldConcurrencyTest {

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    @DisplayName("High-Concurrency: 100 concurrent Virtual Threads fighting for the exact same seat -> Strict 1 Success & 99 Conflicts")
    void test100VirtualThreadsCompeteForSameSeat() throws InterruptedException {
        // 1. Arrange: Create a dedicated test event with 1 seat
        UUID testEventId = UUID.randomUUID();
        Event event = Event.builder()
                .id(testEventId)
                .title("Concurrency Test Event")
                .venueName("Test Arena")
                .totalSeats(1)
                .startsAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .saleStartsAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .createdAt(Instant.now())
                .build();
        eventRepository.save(event);

        UUID contestedSeatId = UUID.randomUUID();
        Seat seat = Seat.builder()
                .id(contestedSeatId)
                .event(event)
                .section("VIP")
                .rowLabel("A")
                .seatNumber("1")
                .price(new BigDecimal("100.00"))
                .status(SeatStatus.AVAILABLE)
                .version(0L)
                .build();
        seatRepository.save(seat);

        // 2. Act: Spawn 100 concurrent Java 21 Virtual Threads firing simultaneously
        int threadCount = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Exception> unexpectedExceptions = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                UUID userId = UUID.randomUUID();
                executor.submit(() -> {
                    try {
                        // Wait for gun shot so all 100 threads hit the database row at the exact same millisecond
                        startGate.await();

                        HoldSeatResponse response = seatHoldService.holdSeats(
                                testEventId,
                                userId,
                                new HoldSeatRequest(List.of(contestedSeatId))
                        );

                        if (response != null && response.holdToken() != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (SeatNotAvailableException e) {
                        conflictCount.incrementAndGet();
                    } catch (Exception e) {
                        unexpectedExceptions.add(e);
                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            // Fire starting gun
            startGate.countDown();
            finishGate.await();
        }

        // 3. Assert: PRD Non-Functional Requirement (Oversell Rate = STRICT ZERO)
        assertThat(unexpectedExceptions).isEmpty();
        assertThat(successCount.get())
                .as("Only 1 thread can acquire the pessimistic lock and hold the seat")
                .isEqualTo(1);
        assertThat(conflictCount.get())
                .as("Remaining 99 threads must receive 409 Conflict (SeatNotAvailableException)")
                .isEqualTo(threadCount - 1);

        // Verify database state
        Seat finalSeat = seatRepository.findById(contestedSeatId).orElseThrow();
        assertThat(finalSeat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(finalSeat.getHeldByUserId()).isNotNull();
        assertThat(finalSeat.getHoldToken()).isNotNull();
    }
}
