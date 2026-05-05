package com.slparcelauctions.backend.admin;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "slpa.admin")
public class AdminBootstrapProperties {
    private List<String> bootstrapUsernames = List.of();
}
