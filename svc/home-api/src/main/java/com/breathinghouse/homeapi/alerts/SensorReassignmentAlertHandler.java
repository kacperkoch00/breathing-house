package com.breathinghouse.homeapi.alerts;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Keeps alert state consistent when a sensor leaves a room. Runs inside the assignment transaction.
 * Existing sensor alerts are resolved for the old room and never moved to the new room; composite
 * room rules are re-evaluated for both rooms against the sensors' current assignments.
 */
@Component
public class SensorReassignmentAlertHandler {

    private final AlertRepository alertRepository;
    private final AlertEvaluationService evaluationService;
    private final AlertConfigurationLoader configurationLoader;
    private final Clock clock;

    public SensorReassignmentAlertHandler(
            AlertRepository alertRepository,
            AlertEvaluationService evaluationService,
            AlertConfigurationLoader configurationLoader,
            Clock clock) {
        this.alertRepository = alertRepository;
        this.evaluationService = evaluationService;
        this.configurationLoader = configurationLoader;
        this.clock = clock;
    }

    public void onSensorLeftRoom(String sensorId, String previousRoomId, String newRoomId) {
        alertRepository.resolveSensorAlertsInRoom(previousRoomId, sensorId, clock.instant());

        Set<String> affectedRooms = new LinkedHashSet<>();
        affectedRooms.add(previousRoomId);
        if (newRoomId != null) {
            affectedRooms.add(newRoomId);
        }
        evaluationService.reevaluateCompositeRooms(configurationLoader.current(), affectedRooms);
    }
}
