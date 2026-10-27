package com.bz.va.web;

import com.bz.va.client.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 原子能力：OCR 识别（同步；大图自动等待下游异步任务完成）。 */
@RestController
@RequestMapping("/api/v1")
public class OcrController {

    private final OcrClient ocrClient;
    private final ObjectMapper mapper;

    public OcrController(OcrClient ocrClient, ObjectMapper mapper) {
        this.ocrClient = ocrClient;
        this.mapper = mapper;
    }

    @PostMapping("/ocr/analyze")
    public JsonNode analyze(
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "options", required = false) String options) {
        JsonNode optionsNode = null;
        if (options != null && !options.isBlank()) {
            try {
                optionsNode = mapper.readTree(options);
            } catch (Exception e) {
                throw new IllegalArgumentException("options 不是合法 JSON");
            }
        }
        byte[] data = read(image);
        return ocrClient.analyze(data, image != null ? image.getOriginalFilename() : null, url, optionsNode);
    }

    static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取上传文件失败: " + file.getOriginalFilename(), e);
        }
    }
}
