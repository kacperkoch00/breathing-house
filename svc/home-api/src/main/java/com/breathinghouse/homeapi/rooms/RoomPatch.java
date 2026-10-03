package com.breathinghouse.homeapi.rooms;

/**
 * A validated partial room update. A {@code null} name means "leave unchanged"; when
 * {@code descriptionProvided} is true the description (possibly {@code null}) replaces the stored value.
 */
public record RoomPatch(String name, boolean descriptionProvided, String description) {
}
