package com.bz.va.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * 下游 RestClient：业务调用长超时（大图 OCR 分钟级），健康检查短超时。
 * multipart 表单字段显式 UTF-8，保证中文（bizno/角色名/ocr_texts）不乱码。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient ocrRestClient(VaProperties props) {
        return build(props.getOcrBaseUrl(), props.getHttpTimeout());
    }

    @Bean
    public RestClient ipcheckRestClient(VaProperties props) {
        return build(props.getIpcheckBaseUrl(), props.getHttpTimeout());
    }

    @Bean
    public RestClient ocrHealthRestClient(VaProperties props) {
        return build(props.getOcrBaseUrl(), Duration.ofSeconds(3));
    }

    @Bean
    public RestClient ipcheckHealthRestClient(VaProperties props) {
        return build(props.getIpcheckBaseUrl(), Duration.ofSeconds(3));
    }

    private RestClient build(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .messageConverters(converters -> converters.stream()
                        .filter(FormHttpMessageConverter.class::isInstance)
                        .map(FormHttpMessageConverter.class::cast)
                        .forEach(converter -> converter.setCharset(StandardCharsets.UTF_8)))
                .build();
    }
}
