package com.flashsale.common.config;

import com.flashsale.event.Event;
import com.flashsale.event.EventRepository;
import com.flashsale.seat.Seat;
import com.flashsale.seat.SeatMapService;
import com.flashsale.seat.SeatRepository;
import com.flashsale.seat.SeatStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    public static final UUID SEED_EVENT_ID = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d");

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatMapService seatMapService;

    @Override
    @Transactional
    public void run(String... args) {
        if (eventRepository.existsById(SEED_EVENT_ID)) {
            log.info("Demo event already seeded: {}", SEED_EVENT_ID);
            return;
        }

        log.info("Seeding demo Flash Sale event and venue seating layout...");

        Event event = Event.builder()
                .id(SEED_EVENT_ID)
                .title("Coldplay: Music of the Spheres World Tour")
                .venueName("Wembley Stadium - Grand Arena")
                .totalSeats(60)
                .startsAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .saleStartsAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .createdAt(Instant.now())
                .build();

        eventRepository.save(event);

        List<Seat> seats = new ArrayList<>();
        String[] sections = {"VIP", "LOWER_BOWL", "UPPER_DECK"};
        BigDecimal[] prices = {new BigDecimal("250.00"), new BigDecimal("120.00"), new BigDecimal("60.00")};

        for (int s = 0; s < sections.length; s++) {
            String section = sections[s];
            BigDecimal price = prices[s];

            for (char row = 'A'; row <= 'B'; row++) {
                for (int num = 1; num <= 10; num++) {
                    Seat seat = Seat.builder()
                            .id(UUID.nameUUIDFromBytes((SEED_EVENT_ID + ":" + section + ":" + row + ":" + num).getBytes()))
                            .event(event)
                            .section(section)
                            .rowLabel(String.valueOf(row))
                            .seatNumber(String.valueOf(num))
                            .price(price)
                            .status(SeatStatus.AVAILABLE)
                            .version(0L)
                            .build();
                    seats.add(seat);
                }
            }
        }

        seatRepository.saveAll(seats);
        log.info("Successfully seeded {} seats for demo event: {}", seats.size(), SEED_EVENT_ID);

        // Pre-warm Redis cache
        seatMapService.getSeatMap(SEED_EVENT_ID);
    }
}
