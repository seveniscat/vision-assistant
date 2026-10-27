package com.bz.va.web;

import com.bz.va.client.IpCheckClient;
import com.bz.va.service.IpConsistencyService;
import com.bz.va.task.TaskManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** IP 标准库管理 + 一致性全检（OCR → 适配 → identify/auto → 汇总报告）。 */
@RestController
@RequestMapping("/api/v1")
public class IpController {

    private final IpCheckClient ipCheckClient;
    private final IpConsistencyService consistencyService;
    private final TaskManager taskManager;
    private final ObjectMapper mapper;

    public IpController(IpCheckClient ipCheckClient, IpConsistencyService consistencyService,
            TaskManager taskManager, ObjectMapper mapper) {
        this.ipCheckClient = ipCheckClient;
        this.consistencyService = consistencyService;
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    // ── 标准库管理 ──

    @GetMapping("/ip/library")
    public JsonNode libraryList(@RequestParam String bizno) {
        requireBizno(bizno);
        return ipCheckClient.libraryList(bizno);
    }

    @PostMapping("/ip/library")
    public JsonNode libraryCreate(
            @RequestParam String bizno,
            @RequestParam String name,
            @RequestParam(value = "model", required = false) String model,
            @RequestPart("files") List<MultipartFile> files) {
        requireBizno(bizno);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("角色名称 name 不能为空");
        }
        if (files == null || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new IllegalArgumentException("至少上传一张标准图（files）");
        }
        return ipCheckClient.libraryCreate(bizno, name.trim(), model,
                files.stream().filter(f -> !f.isEmpty()).toList());
    }

    @DeleteMapping("/ip/library/{name}")
    public JsonNode libraryDelete(@RequestParam String bizno, @PathVariable String name) {
        requireBizno(bizno);
        return ipCheckClient.libraryDelete(bizno, name);
    }

    @GetMapping("/ip/library/images/{imageId}")
    public ResponseEntity<byte[]> libraryImage(@RequestParam String bizno, @PathVariable String imageId) {
        requireBizno(bizno);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(ipCheckClient.libraryImage(bizno, imageId));
    }

    // ── 一致性全检 ──

    /**
     * 上传包装图（或 URL）+ bizno：VA 编排 OCR → 适配 → identify/auto，返回完整报告。
     * async=true 时改为提交异步任务：返回 202 {task_id}，轮询 GET /api/v1/tasks/{task_id}；
     * callbackUrl 完成后回调（带重试，设 VA_WEBHOOK_SECRET 时携带 X-VA-Signature）。
     */
    @PostMapping("/ip/identify")
    public ResponseEntity<JsonNode> identify(
            @RequestParam String bizno,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "includeCrops", required = false, defaultValue = "false") boolean includeCrops,
            @RequestParam(value = "async", required = false, defaultValue = "false") boolean asyncMode,
            @RequestParam(value = "callbackUrl", required = false) String callbackUrl) {
        requireBizno(bizno);
        byte[] data = OcrController.read(image);
        if (data == null && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("图片输入必须二选一：image（文件）或 url");
        }
        if (asyncMode) {
            String taskId = taskManager.submitIpConsistency(data,
                    image != null ? image.getOriginalFilename() : null, url, bizno.trim(), model,
                    includeCrops, callbackUrl);
            ObjectNode body = mapper.createObjectNode();
            body.put("task_id", taskId);
            body.put("recipe", "ip-consistency-full");
            body.put("status", "pending");
            body.put("task_url", "/api/v1/tasks/" + taskId);
            return ResponseEntity.accepted().body(body);
        }
        return ResponseEntity.ok(consistencyService.fullCheck(data,
                image != null ? image.getOriginalFilename() : null, url, bizno.trim(), model, includeCrops));
    }

    private static void requireBizno(String bizno) {
        if (bizno == null || bizno.isBlank()) {
            throw new IllegalArgumentException("bizno（项目编号）不能为空");
        }
    }
}
