package com.breathinghouse.homeapi.alerts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AlertConfigurationLoader {

    private static final Logger log = LoggerFactory.getLogger(AlertConfigurationLoader.class);
    private static final String DEFAULT_CONFIGURATION = "alerts/default-alerts.json";

    private final ObjectMapper objectMapper;
    private final Path externalPath;
    private final AtomicReference<AlertConfiguration.Validated> current = new AtomicReference<>();
    private byte[] loadedDigest;

    public AlertConfigurationLoader(
            ObjectMapper objectMapper,
            @Value("${home-api.alerts.config-path:}") String configPath) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.externalPath = configPath == null || configPath.isBlank() ? null : Path.of(configPath);
    }

    @PostConstruct
    public synchronized void initialize() {
        try {
            byte[] content = readContent();
            current.set(parse(content));
            loadedDigest = digest(content);
            log.info(
                    "loaded alert configuration from {} with {} rules",
                    sourceDescription(),
                    current.get().alerts().size());
        } catch (RuntimeException | IOException ex) {
            throw new IllegalStateException("cannot load alert configuration from " + sourceDescription(), ex);
        }
    }

    public AlertConfiguration.Validated current() {
        AlertConfiguration.Validated configuration = current.get();
        if (configuration == null) {
            throw new IllegalStateException("alert configuration has not been initialized");
        }
        return configuration;
    }

    public synchronized void reloadIfChanged() {
        if (externalPath == null) {
            return;
        }

        try {
            byte[] content = readContent();
            byte[] digest = digest(content);
            if (Arrays.equals(digest, loadedDigest)) {
                return;
            }

            AlertConfiguration.Validated replacement = parse(content);
            current.set(replacement);
            loadedDigest = digest;
            log.info(
                    "reloaded alert configuration from {} with {} rules",
                    sourceDescription(),
                    replacement.alerts().size());
        } catch (RuntimeException | IOException ex) {
            log.error(
                    "alert configuration reload failed; retaining the last valid configuration",
                    ex);
        }
    }

    private AlertConfiguration.Validated parse(byte[] content) throws IOException {
        return objectMapper.readValue(content, AlertConfiguration.class).validate();
    }

    private byte[] readContent() throws IOException {
        if (externalPath != null) {
            return Files.readAllBytes(externalPath);
        }
        return new ClassPathResource(DEFAULT_CONFIGURATION).getContentAsByteArray();
    }

    private String sourceDescription() {
        return externalPath == null ? "classpath:" + DEFAULT_CONFIGURATION : externalPath.toString();
    }

    private static byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
