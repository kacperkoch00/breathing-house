package com.breathinghouse.homeapi.sensors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class SensorController {

    private final SensorService sensorService;

    public SensorController(SensorService sensorService) {
        this.sensorService = sensorService;
    }

    @GetMapping("/sensors")
    public SensorsResponse listSensors() {
        return new SensorsResponse(sensorService.listSensors());
    }

    @PostMapping(path = "/sensors", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SensorSummary> pairSensor(@Valid @RequestBody PairSensorRequest request) {
        PairResult result = sensorService.pair(request.requireSensorId(), request.displayNameOrNull());
        if (result.created()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/sensors/" + result.sensor().sensorId()))
                    .body(result.sensor());
        }
        return ResponseEntity.ok(result.sensor());
    }

    @GetMapping("/sensors/{sensorId}")
    public SensorSummary getSensor(@PathVariable @NotBlank String sensorId) {
        return sensorService.getSensor(sensorId);
    }

    @DeleteMapping("/sensors/{sensorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpairSensor(@PathVariable @NotBlank String sensorId) {
        sensorService.unpair(sensorId);
    }

    @PatchMapping(path = "/sensors/{sensorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SensorSummary updateSensor(
            @PathVariable @NotBlank String sensorId,
            @Valid @RequestBody UpdateSensorRequest request) {
        return sensorService.rename(sensorId, request.requireDisplayName());
    }

    @PutMapping("/rooms/{roomId}/sensors/{sensorId}")
    public SensorSummary assignSensor(
            @PathVariable @NotBlank String roomId,
            @PathVariable @NotBlank String sensorId) {
        return sensorService.assign(roomId, sensorId);
    }

    @DeleteMapping("/rooms/{roomId}/sensors/{sensorId}")
    public SensorSummary unassignSensor(
            @PathVariable @NotBlank String roomId,
            @PathVariable @NotBlank String sensorId) {
        return sensorService.unassign(roomId, sensorId);
    }
}
