package com.qingruan.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Embedding 调用专用 RestTemplate（短超时，独立于 chat 实例）。
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public RestTemplate embeddingRestTemplate(EmbeddingProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        return new RestTemplate(factory);
    }
}
