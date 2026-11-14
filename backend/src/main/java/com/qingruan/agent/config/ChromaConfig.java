package com.qingruan.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Chroma 向量库调用专用 RestTemplate（短超时）。
 */
@Configuration
public class ChromaConfig {

    @Bean
    public RestTemplate chromaRestTemplate(VectorProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getChroma().getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getChroma().getTimeoutMs()));
        return new RestTemplate(factory);
    }
}
