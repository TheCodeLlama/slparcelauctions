package com.slparcelauctions.backend.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
@EnableConfigurationProperties(StorageConfigProperties.class)
@RequiredArgsConstructor
@Slf4j
public class S3ClientConfig {

    private final StorageConfigProperties props;

    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.region()));

        if (props.hasStaticCredentials()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey())));
            log.info("S3 client using static credentials (dev/test profile)");
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
            log.info("S3 client using DefaultCredentialsProvider (prod profile)");
        }

        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.endpointOverride()));
            log.info("S3 endpoint override: {}", props.endpointOverride());
        }

        if (props.pathStyleAccess()) {
            builder.forcePathStyle(true);
        }

        return builder.build();
    }
}
