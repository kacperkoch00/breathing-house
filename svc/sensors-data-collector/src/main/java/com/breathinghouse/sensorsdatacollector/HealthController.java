package com.breathinghouse.sensorsdatacollector;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping(value = "/live", produces = MediaType.TEXT_PLAIN_VALUE)
    public String live() {
        return "OK\n";
    }

    @GetMapping(value = "/ready", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ready() {
        return "READY\n";
    }
}
