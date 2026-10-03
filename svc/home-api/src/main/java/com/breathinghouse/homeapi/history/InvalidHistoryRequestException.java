package com.breathinghouse.homeapi.history;

public class InvalidHistoryRequestException extends RuntimeException {

    public InvalidHistoryRequestException(String message) {
        super(message);
    }
}
