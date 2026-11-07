package com.flashsale.seat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.seat.dto.SeatUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatStreamService implements MessageListener {

    private static final Long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes
    private final ObjectMapper objectMapper;

    // In-memory mapping of eventId -> List of active SseEmitter connections
    private final Map<UUID, List<SseEmitter>> emittersByEvent = new ConcurrentHashMap<>();

    /**
     * Subscribes a client browser tab to real-time seat update events for a specific event.
     */
    public SseEmitter subscribe(UUID eventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emittersByEvent.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            List<SseEmitter> list = emittersByEvent.get(eventId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    emittersByEvent.remove(eventId);
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Send initial connection establishment ping
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("Connected to live seat updates stream for event " + eventId));
        } catch (IOException e) {
            cleanup.run();
        }

        return emitter;
    }

    /**
     * Redis Pub/Sub MessageListener: Receives seat updates published across any backend pod in the cluster,
     * and broadcasts them to local SseEmitters.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SeatUpdateMessage update = objectMapper.readValue(message.getBody(), SeatUpdateMessage.class);
            broadcastLocal(update);
        } catch (Exception e) {
            log.error("Failed to deserialize Redis Pub/Sub seat update message", e);
        }
    }

    public void broadcastLocal(SeatUpdateMessage update) {
        List<SseEmitter> emitters = emittersByEvent.get(update.eventId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("seat-update")
                        .data(update));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }
}
