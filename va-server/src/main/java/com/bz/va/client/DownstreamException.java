package com.bz.va.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/** 下游能力调用失败（网络/超时/下游业务错误），已归一化为 VA 错误码。 */
public class DownstreamException extends RuntimeException {

    private final String provider;
    private final String code;
    private final int status; // 返回给调用方的 HTTP 状态：下游 4xx 透传，5xx 归为 502
    private final transient JsonNode detail;

    public DownstreamException(String provider, String code, int status, String message, JsonNode detail) {
        super(message);
        this.provider = provider;
        this.code = code;
        this.status = status;
        this.detail = detail;
    }

    public String getProvider() {
        return provider;
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }

    public JsonNode getDetail() {
        return detail;
    }

    public static DownstreamException unreachable(String provider, String message) {
        return new DownstreamException(provider, "UNAVAILABLE", 502, message, null);
    }

    public static DownstreamException fromHttp(String provider, String path, int statusCode, String bodyText,
                                               ObjectMapper mapper) {
        String code = switch (statusCode) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "UNPROCESSABLE";
            case 503 -> "UNAVAILABLE";
            default -> statusCode >= 500 ? "UPSTREAM_ERROR" : "BAD_REQUEST";
        };
        int httpStatus = statusCode < 500 ? statusCode : 502;
        JsonNode detail;
        try {
            detail = mapper.readTree(bodyText);
        } catch (Exception e) {
            detail = TextNode.valueOf(bodyText.length() > 500 ? bodyText.substring(0, 500) : bodyText);
        }
        return new DownstreamException(provider, code, httpStatus,
                provider + " " + path + " 返回 " + statusCode, detail);
    }
}
