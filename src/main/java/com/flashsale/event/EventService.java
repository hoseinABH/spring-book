package com.flashsale.event;

import com.flashsale.common.exception.ResourceNotFoundException;
import com.flashsale.event.dto.EventResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID eventId) {
        return eventRepository.findById(eventId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with ID: " + eventId));
    }

    public EventResponse mapToResponse(Event event) {
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getVenueName(),
                event.getTotalSeats(),
                event.getStartsAt(),
                event.getSaleStartsAt(),
                event.getCreatedAt()
        );
    }
}
