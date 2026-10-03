package com.breathinghouse.homeapi;

import com.breathinghouse.homeapi.history.HistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final HistoryRepository historyRepository;

    public HealthController(HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_PLAIN_VALUE)
    public String live() {
        return "OK\n";
    }

    @GetMapping(value = "/ready", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ready() {
        if (historyRepository.isReady()) {
            return ResponseEntity.ok("READY\n");
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("NOT_READY\n");
    }
}
