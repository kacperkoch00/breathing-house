package com.breathinghouse.homeapi.gateway;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class GatewayStatusController {

    private final GatewayStatusService gatewayStatusService;

    public GatewayStatusController(GatewayStatusService gatewayStatusService) {
        this.gatewayStatusService = gatewayStatusService;
    }

    @GetMapping("/sensor-gateway/status")
    public GatewayStatusResponse status() {
        return new GatewayStatusResponse(gatewayStatusService.isOnline());
    }
}
