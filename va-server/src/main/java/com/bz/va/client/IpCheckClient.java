package com.bz.va.client;

import com.bz.va.config.VaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/** packvision ip_check 适配器：标准库管理 + identify/auto 核心接口。 */
@Component
public class IpCheckClient extends AbstractDownstreamClient {

    public IpCheckClient(VaProperties properties,
            @Qualifier("ipcheckRestClient") RestClient client,
            @Qualifier("ipcheckHealthRestClient") RestClient healthClient,
            ObjectMapper mapper) {
        super("ipcheck", client, healthClient, mapper);
    }

    public JsonNode health() {
        return health("/api/ip/health");
    }

    /** 列出项目内全部角色与图片。 */
    public JsonNode libraryList(String bizno) {
        return getJson("/api/ip/library?bizno={bizno}", bizno);
    }

    /** 创建角色 / 追加标准图。 */
    public JsonNode libraryCreate(String bizno, String name, String model, List<MultipartFile> files) {
        var parts = form();
        parts.add("name", name);
        if (model != null && !model.isBlank()) {
            parts.add("model", model);
        }
        for (MultipartFile file : files) {
            try {
                parts.add("files", resource(file.getBytes(), file.getOriginalFilename()));
            } catch (IOException e) {
                throw new DownstreamException(provider(), "BAD_REQUEST", 400,
                        "读取上传文件失败: " + file.getOriginalFilename(), null);
            }
        }
        return postMultipartJson("/api/ip/library?bizno={bizno}", parts, bizno);
    }

    public JsonNode libraryDelete(String bizno, String name) {
        return deleteJson("/api/ip/library/{name}?bizno={bizno}", name, bizno);
    }

    /** 读取标准图文件（bytes，供前端 <img> 展示）。 */
    public byte[] libraryImage(String bizno, String imageId) {
        return getBytes("/api/ip/library/images/{imageId}?bizno={bizno}", imageId, bizno);
    }

    /**
     * 自动框选识别 + 图文一致性判定。
     *
     * @param ocrTexts 适配后的 OCR 文本框（JsonNode 数组），像素坐标直接传（下游自动归一化）
     */
    public JsonNode identifyAuto(String bizno, byte[] image, String filename, String url, JsonNode ocrTexts,
            String model, boolean includeCrops) {
        var parts = form();
        if (ocrTexts != null) {
            parts.add("ocr_texts", ocrTexts.toString());
        }
        if (model != null && !model.isBlank()) {
            parts.add("model", model);
        }
        if (includeCrops) {
            parts.add("include_crops", "true");
        }
        if (image != null) {
            parts.add("image", resource(image, filename));
        } else if (url != null && !url.isBlank()) {
            parts.add("image_url", url);
        } else {
            throw new DownstreamException(provider(), "BAD_REQUEST", 400, "缺少图片：需提供 image 文件或 url", null);
        }
        return postMultipartJson("/api/ip/identify/auto?bizno={bizno}", parts, bizno);
    }
}
