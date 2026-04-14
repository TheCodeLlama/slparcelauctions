package com.slparcelauctions.backend.sl;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SlConfigProperties.class)
public class SlPropertiesConfig {
}
