package com.breathinghouse.homeapi;

import com.breathinghouse.homeapi.alerts.AlertRepository;
import com.breathinghouse.homeapi.gateway.GatewayHeartbeatRepository;
import com.breathinghouse.homeapi.history.HistoryRepository;
import com.breathinghouse.homeapi.rooms.RoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final HistoryRepository historyRepository;
    private final AlertRepository alertRepository;
    private final GatewayHeartbeatRepository gatewayHeartbeatRepository;
    private final RoomRepository roomRepository;

    public HealthController(
            HistoryRepository historyRepository,
            AlertRepository alertRepository,
            GatewayHeartbeatRepository gatewayHeartbeatRepository,
            RoomRepository roomRepository) {
        this.historyRepository = historyRepository;
        this.alertRepository = alertRepository;
        this.gatewayHeartbeatRepository = gatewayHeartbeatRepository;
        this.roomRepository = roomRepository;
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_PLAIN_VALUE)
    public String live() {
        return "OK\n";
    }

    @GetMapping(value = "/ready", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ready() {
        if (historyRepository.isReady()
                && alertRepository.isReady()
                && gatewayHeartbeatRepository.isReady()
                && roomRepository.isReady()) {
            return ResponseEntity.ok("READY\n");
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("NOT_READY\n");
    }
}
