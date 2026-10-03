package com.breathinghouse.homeapi.rooms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping(path = "/rooms", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RoomSummary> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        RoomSummary created = roomService.createRoom(request.requireName(), request.descriptionOrNull());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/rooms/" + created.roomId()))
                .body(created);
    }

    @GetMapping("/rooms")
    public RoomsResponse listRooms() {
        return new RoomsResponse(roomService.listRooms());
    }

    @GetMapping("/rooms/{roomId}")
    public RoomSummary getRoom(@PathVariable @NotBlank String roomId) {
        return roomService.getRoom(roomId);
    }

    @PatchMapping(path = "/rooms/{roomId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RoomSummary updateRoom(
            @PathVariable @NotBlank String roomId,
            @Valid @RequestBody UpdateRoomRequest request) {
        return roomService.updateRoom(roomId, request.toPatch());
    }
}
