package com.bz.va.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 下游调用的公共封装：错误归一化 + multipart 转发。URI 模板变量自动编码（支持中文 bizno/角色名）。 */
public abstract class AbstractDownstreamClient {

    protected final RestClient client;
    protected final RestClient healthClient;
    protected final ObjectMapper mapper;
    private final String provider;

    protected AbstractDownstreamClient(String provider, RestClient client, RestClient healthClient,
            ObjectMapper mapper) {
        this.provider = provider;
        this.client = client;
        this.healthClient = healthClient;
        this.mapper = mapper;
    }

    protected String provider() {
        return provider;
    }

    /** 健康探测：可达返回下游 JSON，不可达返回 null（由调用方组装为 ok:false）。 */
    public JsonNode health(String path) {
        try {
            return healthClient.get().uri(path).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            return null;
        }
    }

    protected JsonNode getJson(String path, Object... uriVars) {
        try {
            return client.get().uri(path, uriVars).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw DownstreamException.fromHttp(provider, path, e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8), mapper);
        } catch (ResourceAccessException e) {
            throw DownstreamException.unreachable(provider, provider + " 不可达: " + e.getMessage());
        }
    }

    protected byte[] getBytes(String path, Object... uriVars) {
        try {
            return client.get().uri(path, uriVars).retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            throw DownstreamException.fromHttp(provider, path, e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8), mapper);
        } catch (ResourceAccessException e) {
            throw DownstreamException.unreachable(provider, provider + " 不可达: " + e.getMessage());
        }
    }

    protected JsonNode deleteJson(String path, Object... uriVars) {
        try {
            return client.delete().uri(path, uriVars).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw DownstreamException.fromHttp(provider, path, e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8), mapper);
        } catch (ResourceAccessException e) {
            throw DownstreamException.unreachable(provider, provider + " 不可达: " + e.getMessage());
        }
    }

    protected JsonNode postMultipartJson(String path, MultiValueMap<String, Object> parts, Object... uriVars) {
        try {
            return client.post().uri(path, uriVars)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw DownstreamException.fromHttp(provider, path, e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8), mapper);
        } catch (ResourceAccessException e) {
            throw DownstreamException.unreachable(provider, provider + " 不可达: " + e.getMessage());
        }
    }

    protected record ResponseWithStatus(int status, JsonNode body) {
    }

    /** 与 postMultipartJson 相同，但保留响应状态码（OCR /analyze 需要区分 200/202）。 */
    protected ResponseWithStatus postMultipartCaptureStatus(String path, MultiValueMap<String, Object> parts) {
        try {
            return client.post().uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .exchange((request, response) -> {
                        String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        int code = response.getStatusCode().value();
                        if (code >= 200 && code < 300) {
                            return new ResponseWithStatus(code, mapper.readTree(text));
                        }
                        throw DownstreamException.fromHttp(provider, path, code, text, mapper);
                    });
        } catch (UncheckedIOException e) {
            throw new DownstreamException(provider, "UPSTREAM_ERROR", 502,
                    provider + " 响应读取失败: " + e.getMessage(), null);
        } catch (ResourceAccessException e) {
            throw DownstreamException.unreachable(provider, provider + " 不可达: " + e.getMessage());
        }
    }

    protected ByteArrayResource resource(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "upload.png";
            }
        };
    }

    protected static MultiValueMap<String, Object> form() {
        return new LinkedMultiValueMap<>();
    }

    protected static String urlEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
