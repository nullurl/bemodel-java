package com.bemodel.industrial;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("forgepilot")
public class ConnectorFactoryInitializer implements ApplicationRunner {
    private final ConnectorFactoryService service;
    public ConnectorFactoryInitializer(ConnectorFactoryService service) { this.service = service; }
    @Override public void run(ApplicationArguments args) { service.bootstrap(); }
}
