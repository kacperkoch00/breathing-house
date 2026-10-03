package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.Severity;
import com.breathinghouse.homeapi.history.InvalidHistoryRequestException;
import com.breathinghouse.homeapi.history.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping(path = "/api/v1/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class AlertController {

    private final AlertRepository alertRepository;

    public AlertController(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @GetMapping
    public PageResponse<Alert> alerts(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String sensorId,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidHistoryRequestException("'from' must be less than or equal to 'to'");
        }
        return alertRepository.findAlerts(status, roomId, sensorId, severity, from, to, limit, offset);
    }

    @GetMapping("/{id}")
    public AlertDetail alert(@PathVariable long id) {
        return alertRepository.findAlertById(id).orElseThrow(() -> new AlertNotFoundException(id));
    }
}
