package com.flashsale.seat;

import com.flashsale.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_seats_event_section_row_number",
            columnNames = {"event_id", "section", "row_label", "seat_number"}
        )
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 32)
    private String section;

    @Column(name = "row_label", nullable = false, length = 16)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, length = 16)
    private String seatNumber;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "held_by_user_id")
    private UUID heldByUserId;

    @Column(name = "hold_token")
    private UUID holdToken;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    /**
     * Optimistic lock token: auto-incremented by Hibernate on every UPDATE.
     */
    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    public boolean isHeldExpired() {
        return status == SeatStatus.HELD && holdExpiresAt != null && holdExpiresAt.isBefore(Instant.now());
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE || isHeldExpired();
    }
}
