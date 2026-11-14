package com.qingruan.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 为 llama.cpp server 端点配置带超时控制的 RestTemplate。
 * <p>
 * 本地 Qwen3.5-9B-Q4 带思维链推理，单次响应可能达到数十秒，故 read 超时取配置值
 * （默认 120s），避免调用方过早断开导致分析失败。
 */
@Configuration
@RequiredArgsConstructor
public class LlamaServerConfig {

    private final AiAnalysisProperties props;

    @Bean
    public RestTemplate llamaRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout(props.getTimeoutMs());
        return new RestTemplate(factory);
    }
}
