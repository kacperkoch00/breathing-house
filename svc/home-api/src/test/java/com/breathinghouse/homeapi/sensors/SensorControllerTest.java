package com.breathinghouse.homeapi.sensors;

import com.breathinghouse.homeapi.config.CorsConfig;
import com.breathinghouse.homeapi.history.HistoryExceptionHandler;
import com.breathinghouse.homeapi.rooms.RoomNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SensorController.class)
@Import({HistoryExceptionHandler.class, CorsConfig.class})
class SensorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorService sensorService;

    @Test
    void listSensorsReturnsSensorShape() throws Exception {
        when(sensorService.listSensors()).thenReturn(List.of(
                new SensorSummary("air-1", "air-1", List.of("AIR"), "room-a"),
                new SensorSummary("hub-1", "Hub", List.of("OPENING", "PRESENCE"), null)));

        mockMvc.perform(get("/api/v1/sensors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensors", hasSize(2)))
                .andExpect(jsonPath("$.sensors[0].sensorId").value("air-1"))
                .andExpect(jsonPath("$.sensors[0].displayName").value("air-1"))
                .andExpect(jsonPath("$.sensors[0].types[0]").value("AIR"))
                .andExpect(jsonPath("$.sensors[0].roomId").value("room-a"))
                .andExpect(jsonPath("$.sensors[1].types", hasSize(2)))
                .andExpect(jsonPath("$.sensors[1].roomId").value(nullValue()));
    }

    @Test
    void postPairsNewSensorWith201() throws Exception {
        when(sensorService.pair("air-11", null))
                .thenReturn(new PairResult(new SensorSummary("air-11", "air-11", List.of(), null), true));

        mockMvc.perform(post("/api/v1/sensors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensorId\":\"  air-11 \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/sensors/air-11"))
                .andExpect(jsonPath("$.sensorId").value("air-11"))
                .andExpect(jsonPath("$.roomId").value(nullValue()));
    }

    @Test
    void postPairExistingSensorReturns200() throws Exception {
        when(sensorService.pair("air-11", "Kitchen"))
                .thenReturn(new PairResult(new SensorSummary("air-11", "Kitchen", List.of(), null), false));

        mockMvc.perform(post("/api/v1/sensors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensorId\":\"air-11\",\"displayName\":\"Kitchen\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Kitchen"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"sensorId\":null}",
            "{\"sensorId\":\"   \"}",
            "{\"sensorId\":123}"
    })
    void postPairRejectsInvalidBodies(String body) throws Exception {
        mockMvc.perform(post("/api/v1/sensors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verify(sensorService, never()).pair(any(), any());
    }

    @Test
    void deleteUnpairsSensor() throws Exception {
        mockMvc.perform(delete("/api/v1/sensors/air-11"))
                .andExpect(status().isNoContent());
        verify(sensorService).unpair("air-11");
    }

    @Test
    void deleteUnpairUnknownSensorReturns404() throws Exception {
        doThrow(new SensorNotFoundException("ghost")).when(sensorService).unpair("ghost");

        mockMvc.perform(delete("/api/v1/sensors/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void getSensorReturnsSensorAndUnknownReturns404() throws Exception {
        when(sensorService.getSensor("air-1"))
                .thenReturn(new SensorSummary("air-1", "Air", List.of("AIR"), null));
        when(sensorService.getSensor("ghost")).thenThrow(new SensorNotFoundException("ghost"));

        mockMvc.perform(get("/api/v1/sensors/air-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorId").value("air-1"))
                .andExpect(jsonPath("$.displayName").value("Air"));
        mockMvc.perform(get("/api/v1/sensors/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Sensor 'ghost' was not found"));
    }

    @Test
    void patchRenamesSensorWithTrimmedDisplayName() throws Exception {
        when(sensorService.rename("air-1", "Kitchen air"))
                .thenReturn(new SensorSummary("air-1", "Kitchen air", List.of("AIR"), "room-a"));

        mockMvc.perform(patch("/api/v1/sensors/air-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  Kitchen air \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Kitchen air"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"displayName\":null}",
            "{\"displayName\":\"   \"}",
            "{\"displayName\":123}",
            "{\"name\":\"Air\"}"
    })
    void patchRejectsInvalidBodies(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/sensors/air-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verify(sensorService, never()).rename(any(), any());
    }

    @Test
    void patchRejectsTooLongDisplayName() throws Exception {
        mockMvc.perform(patch("/api/v1/sensors/air-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at most 100")));
    }

    @Test
    void patchUnknownSensorReturns404() throws Exception {
        when(sensorService.rename("ghost", "Ghost")).thenThrow(new SensorNotFoundException("ghost"));

        mockMvc.perform(patch("/api/v1/sensors/ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void putAssignsSensorAndReturnsUpdatedSensor() throws Exception {
        when(sensorService.assign("room-b", "air-1"))
                .thenReturn(new SensorSummary("air-1", "air-1", List.of("AIR"), "room-b"));

        mockMvc.perform(put("/api/v1/rooms/room-b/sensors/air-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorId").value("air-1"))
                .andExpect(jsonPath("$.roomId").value("room-b"));
    }

    @Test
    void putReturns404ForUnknownRoomOrSensor() throws Exception {
        when(sensorService.assign("ghost", "air-1")).thenThrow(new RoomNotFoundException("ghost"));
        when(sensorService.assign("room-b", "ghost")).thenThrow(new SensorNotFoundException("ghost"));

        mockMvc.perform(put("/api/v1/rooms/ghost/sensors/air-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Room 'ghost' was not found"));
        mockMvc.perform(put("/api/v1/rooms/room-b/sensors/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Sensor 'ghost' was not found"));
    }

    @Test
    void deleteUnassignsSensorAndReturnsUpdatedSensor() throws Exception {
        when(sensorService.unassign("room-b", "air-1"))
                .thenReturn(new SensorSummary("air-1", "air-1", List.of("AIR"), null));

        mockMvc.perform(delete("/api/v1/rooms/room-b/sensors/air-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sensorId").value("air-1"))
                .andExpect(jsonPath("$.roomId").value(nullValue()));
    }

    @Test
    void deleteReturns404ForUnknownRoomOrSensor() throws Exception {
        when(sensorService.unassign("ghost", "air-1")).thenThrow(new RoomNotFoundException("ghost"));
        when(sensorService.unassign("room-b", "ghost")).thenThrow(new SensorNotFoundException("ghost"));

        mockMvc.perform(delete("/api/v1/rooms/ghost/sensors/air-1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/rooms/room-b/sensors/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns409WhenSensorIsNotInThatRoom() throws Exception {
        when(sensorService.unassign("room-b", "air-1"))
                .thenThrow(new SensorAssignmentConflictException("air-1", "room-b"));

        mockMvc.perform(delete("/api/v1/rooms/room-b/sensors/air-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andExpect(jsonPath("$.message")
                        .value("Sensor 'air-1' is not currently assigned to room 'room-b'"));
    }

    @Test
    void databaseFailureReturns500() throws Exception {
        when(sensorService.listSensors()).thenThrow(new DataAccessResourceFailureException("down"));

        mockMvc.perform(get("/api/v1/sensors"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("database_error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "PATCH", "GET", "POST"})
    void corsPreflightAllowsMutationMethodsOnSensorPaths(String method) throws Exception {
        String path = switch (method) {
            case "PATCH", "GET" -> "/api/v1/sensors/air-1";
            case "POST" -> "/api/v1/sensors";
            default -> "/api/v1/rooms/room-b/sensors/air-1";
        };

        mockMvc.perform(options(path)
                        .header("Origin", "http://home-dashboard.local")
                        .header("Access-Control-Request-Method", method))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://home-dashboard.local"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString(method)));

        verifyNoInteractions(sensorService);
    }

    @Test
    void corsPreflightRejectsUnrelatedOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/rooms/room-b/sensors/air-1")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
