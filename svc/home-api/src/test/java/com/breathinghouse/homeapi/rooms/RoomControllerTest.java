package com.breathinghouse.homeapi.rooms;

import com.breathinghouse.homeapi.config.CorsConfig;
import com.breathinghouse.homeapi.history.HistoryExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RoomController.class)
@Import({HistoryExceptionHandler.class, CorsConfig.class})
class RoomControllerTest {

    private static final String ROOM_ID = "0b0d8f06-7d51-4a54-9c6f-3a0f8c5e0d11";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @Test
    void createReturns201WithGeneratedRoomShape() throws Exception {
        when(roomService.createRoom("Living Room", "Ground floor"))
                .thenReturn(new RoomSummary(ROOM_ID, "Living Room", "Ground floor", List.of()));

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Living Room \",\"description\":\" Ground floor \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/rooms/" + ROOM_ID))
                .andExpect(jsonPath("$.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.name").value("Living Room"))
                .andExpect(jsonPath("$.description").value("Ground floor"))
                .andExpect(jsonPath("$.sensorIds", hasSize(0)))
                .andExpect(jsonPath("$.displayName").doesNotExist());
    }

    @Test
    void createWithoutDescriptionPassesNull() throws Exception {
        when(roomService.createRoom(eq("Bedroom"), isNull()))
                .thenReturn(new RoomSummary(ROOM_ID, "Bedroom", null, List.of()));

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bedroom\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"name\":null}",
            "{\"name\":\"   \"}",
            "{\"name\":123}",
            "{\"name\":\"Ok\",\"description\":5}",
            "[]",
            "not json"
    })
    void createRejectsInvalidBodies(String body) throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verifyNoInteractions(roomService);
    }

    @Test
    void createRejectsTooLongNameAndDescription() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ok\",\"description\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(roomService);
    }

    @Test
    void createAcceptsBoundaryLengths() throws Exception {
        String name = "n".repeat(100);
        String description = "d".repeat(500);
        when(roomService.createRoom(name, description))
                .thenReturn(new RoomSummary(ROOM_ID, name, description, List.of()));

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listRoomsReturnsRoomsWithSensorIds() throws Exception {
        when(roomService.listRooms()).thenReturn(List.of(
                new RoomSummary("room-a", "Bedroom", null, List.of()),
                new RoomSummary("room-b", "Living Room", "Open plan", List.of("air-1", "room-1"))));

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].roomId").value("room-a"))
                .andExpect(jsonPath("$.rooms[0].name").value("Bedroom"))
                .andExpect(jsonPath("$.rooms[0].sensorIds", hasSize(0)))
                .andExpect(jsonPath("$.rooms[1].description").value("Open plan"))
                .andExpect(jsonPath("$.rooms[1].sensorIds[0]").value("air-1"))
                .andExpect(jsonPath("$.rooms[1].sensorIds[1]").value("room-1"));
    }

    @Test
    void getRoomReturnsRoom() throws Exception {
        when(roomService.getRoom("room-b"))
                .thenReturn(new RoomSummary("room-b", "Living Room", null, List.of("air-1")));

        mockMvc.perform(get("/api/v1/rooms/room-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("room-b"))
                .andExpect(jsonPath("$.name").value("Living Room"))
                .andExpect(jsonPath("$.sensorIds[0]").value("air-1"));
    }

    @Test
    void getUnknownRoomReturns404() throws Exception {
        when(roomService.getRoom("ghost")).thenThrow(new RoomNotFoundException("ghost"));

        mockMvc.perform(get("/api/v1/rooms/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Room 'ghost' was not found"));
    }

    @Test
    void patchNameOnlyLeavesDescriptionUntouched() throws Exception {
        when(roomService.updateRoom(eq("room-b"), any()))
                .thenReturn(new RoomSummary("room-b", "Salon", "Open plan", List.of()));

        mockMvc.perform(patch("/api/v1/rooms/room-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Salon \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Salon"))
                .andExpect(jsonPath("$.description").value("Open plan"));

        ArgumentCaptor<RoomPatch> patch = ArgumentCaptor.forClass(RoomPatch.class);
        verify(roomService).updateRoom(eq("room-b"), patch.capture());
        assertThat(patch.getValue())
                .isEqualTo(new RoomPatch("Salon", false, null));
    }

    @Test
    void patchDescriptionNullClearsDescription() throws Exception {
        when(roomService.updateRoom(eq("room-b"), any()))
                .thenReturn(new RoomSummary("room-b", "Salon", null, List.of()));

        mockMvc.perform(patch("/api/v1/rooms/room-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()));

        ArgumentCaptor<RoomPatch> patch = ArgumentCaptor.forClass(RoomPatch.class);
        verify(roomService).updateRoom(eq("room-b"), patch.capture());
        assertThat(patch.getValue())
                .isEqualTo(new RoomPatch(null, true, null));
    }

    @Test
    void patchNameAndDescriptionTogether() throws Exception {
        when(roomService.updateRoom(eq("room-b"), any()))
                .thenReturn(new RoomSummary("room-b", "Salon", "Cosy", List.of()));

        mockMvc.perform(patch("/api/v1/rooms/room-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Salon\",\"description\":\"Cosy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Cosy"));

        verify(roomService).updateRoom("room-b", new RoomPatch("Salon", true, "Cosy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"displayName\":\"Living Room\"}",
            "{\"name\":null}",
            "{\"name\":\"   \"}",
            "{\"name\":123}",
            "{\"description\":42}"
    })
    void patchRejectsEmptyOrInvalidBodies(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/room-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verify(roomService, never()).updateRoom(any(), any());
    }

    @Test
    void patchRejectsTooLongName() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/room-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at most 100")));
    }

    @Test
    void patchUnknownRoomReturns404() throws Exception {
        when(roomService.updateRoom(eq("ghost"), any())).thenThrow(new RoomNotFoundException("ghost"));

        mockMvc.perform(patch("/api/v1/rooms/ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Room 'ghost' was not found"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/rooms/room-b"))
                .andExpect(status().isNoContent());
        verify(roomService).deleteRoom("room-b");
    }

    @Test
    void deleteMissingRoomReturns404() throws Exception {
        org.mockito.Mockito.doThrow(new RoomNotFoundException("ghost"))
                .when(roomService).deleteRoom("ghost");

        mockMvc.perform(delete("/api/v1/rooms/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.message").value("Room 'ghost' was not found"));
    }

    @Test
    void repositoryFailureReturns500() throws Exception {
        when(roomService.listRooms())
                .thenThrow(new DataAccessResourceFailureException("down"));

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("database_error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PATCH", "PUT", "DELETE"})
    void corsPreflightAllowsConfiguredOriginForMutationMethods(String method) throws Exception {
        mockMvc.perform(options("/api/v1/rooms/room-b")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", method))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString(method)));
    }

    @Test
    void corsRejectsUnrelatedOriginForPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/room-b")
                        .header("Origin", "http://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Living Room\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
