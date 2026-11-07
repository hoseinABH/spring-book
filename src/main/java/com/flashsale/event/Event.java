package com.flashsale.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "venue_name", nullable = false)
    private String venueName;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sale_starts_at", nullable = false)
    private Instant saleStartsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
