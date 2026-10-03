package com.breathinghouse.homeapi.sensors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/sensors/{sensorId}")
    public SensorSummary getSensor(@PathVariable @NotBlank String sensorId) {
        return sensorService.getSensor(sensorId);
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
