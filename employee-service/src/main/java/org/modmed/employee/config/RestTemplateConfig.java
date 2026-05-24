package org.modmed.employee.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    // Socket-level timeouts complement Resilience4j's TimeLimiter.
    // TimeLimiter cancels the CompletableFuture; these timeouts prevent
    // the underlying TCP socket from hanging indefinitely on sync calls.
    @Value("${rest.client.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${rest.client.read-timeout-ms:3000}")
    private int readTimeoutMs;

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
