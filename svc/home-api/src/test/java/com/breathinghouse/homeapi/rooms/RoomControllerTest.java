package com.breathinghouse.homeapi.rooms;

import com.breathinghouse.homeapi.config.CorsConfig;
import com.breathinghouse.homeapi.history.HistoryExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RoomController.class)
@Import({HistoryExceptionHandler.class, CorsConfig.class})
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @Test
    void listRoomsReturnsRoomObjects() throws Exception {
        when(roomService.listRooms()).thenReturn(List.of(
                new RoomSummary("bedroom", "bedroom"),
                new RoomSummary("living-room", "Living Room")));

        mockMvc.perform(get("/api/v1/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].roomId").value("bedroom"))
                .andExpect(jsonPath("$.rooms[0].displayName").value("bedroom"))
                .andExpect(jsonPath("$.rooms[1].roomId").value("living-room"))
                .andExpect(jsonPath("$.rooms[1].displayName").value("Living Room"));
    }

    @Test
    void patchReturnsUpdatedRoom() throws Exception {
        when(roomService.rename("living-room", "Living Room"))
                .thenReturn(new RoomSummary("living-room", "Living Room"));

        mockMvc.perform(patch("/api/v1/rooms/living-room")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Living Room\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("living-room"))
                .andExpect(jsonPath("$.displayName").value("Living Room"));
    }

    @Test
    void blankDisplayNameReturns400() throws Exception {
        when(roomService.rename(eq("living-room"), any()))
                .thenThrow(new IllegalArgumentException("displayName must not be blank"));

        mockMvc.perform(patch("/api/v1/rooms/living-room")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void missingDisplayNameReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/living-room")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void nonStringDisplayNameReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/living-room")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void nameLongerThan100CharactersReturns400() throws Exception {
        when(roomService.rename(eq("living-room"), any()))
                .thenThrow(new IllegalArgumentException("displayName must be at most 100 characters"));

        String tooLong = "x".repeat(101);
        mockMvc.perform(patch("/api/v1/rooms/living-room")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void unknownRoomReturns404() throws Exception {
        when(roomService.rename("ghost", "Ghost"))
                .thenThrow(new RoomNotFoundException("ghost"));

        mockMvc.perform(patch("/api/v1/rooms/ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ghost\"}"))
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

    @Test
    void corsAllowsConfiguredOriginForPatch() throws Exception {
        mockMvc.perform(options("/api/v1/rooms/living-room")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test
    void corsRejectsUnrelatedOriginForPatch() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/living-room")
                        .header("Origin", "http://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Living Room\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
