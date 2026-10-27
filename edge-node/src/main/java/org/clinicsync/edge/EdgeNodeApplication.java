package org.clinicsync.edge;

import org.clinicsync.edge.transport.GzipClientInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class EdgeNodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeNodeApplication.class, args);
    }

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .requestInterceptor(new GzipClientInterceptor())
                .build();
    }
}
