package com.aiops.module.kb.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.kb.entity.KbChunk;
import com.aiops.module.kb.entity.KbDocument;
import com.aiops.module.kb.service.KbDocumentService;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 - 文档管理（S2 kb/Document）
 * 提供：列表 / 创建（手输 or 上传文件）/ 编辑 / 删除 / 分块。实现见 {@link KbDocumentService}。
 */
@Tag(name = "知识库 文档")
@RestController
@RequestMapping("/api/kb/document")
@RequiredArgsConstructor
public class KbDocumentController {

    private final KbDocumentService kbDocumentService;

    @Operation(summary = "分页列表")
    @RequirePerm("kb:doc:list")
    @GetMapping
    public Result<Page<KbDocument>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String keyword) {
        return Result.ok(kbDocumentService.page(current, size, keyword));
    }

    @Operation(summary = "详情（含 content）")
    @RequirePerm("kb:doc:list")
    @GetMapping("/{id}")
    public Result<KbDocument> detail(@PathVariable Long id) {
        return Result.ok(kbDocumentService.detail(id));
    }

    @Operation(summary = "新建（手输）")
    @RequirePerm("kb:doc:list")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody KbDocument body) {
        KbDocument doc = kbDocumentService.create(body);
        Map<String, Object> r = new HashMap<>();
        r.put("id", doc.getId());
        r.put("chunkCount", doc.getChunkCount());
        return Result.ok(r);
    }

    @Operation(summary = "上传文档（txt/md）")
    @RequirePerm("kb:doc:list")
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public Result<Map<String, Object>> upload(@RequestPart("file") MultipartFile file,
                                              @RequestParam(required = false) String title,
                                              @RequestParam(required = false) String tags) throws java.io.IOException {
        if (file.isEmpty()) throw new BizException("文件为空");
        String filename = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename();
        String lower = filename.toLowerCase();
        String docType;
        if (lower.endsWith(".md")) docType = "md";
        else if (lower.endsWith(".txt")) docType = "txt";
        else throw new BizException("仅支持 .txt / .md（docx 解析留待后续 POI 接入）");

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.isBlank()) throw new BizException("文件正文为空");
        String finalTitle = (title != null && !title.isBlank()) ? title : filename;

        KbDocument doc = new KbDocument();
        doc.setTitle(finalTitle);
        doc.setDocType(docType);
        doc.setContent(content);
        doc.setSource(filename);
        doc.setTags(tags);
        kbDocumentService.create(doc);

        Map<String, Object> r = new HashMap<>();
        r.put("id", doc.getId());
        r.put("title", finalTitle);
        r.put("chunkCount", doc.getChunkCount());
        return Result.ok(r);
    }

    @Operation(summary = "编辑（替换 content 会重做分块）")
    @RequirePerm("kb:doc:list")
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody KbDocument body) {
        int chunks = kbDocumentService.update(body);
        Map<String, Object> r = new HashMap<>();
        r.put("id", body.getId());
        r.put("chunkCount", chunks);
        return Result.ok(r);
    }

    @Operation(summary = "删除（软删）")
    @RequirePerm("kb:doc:list")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbDocumentService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "查看某文档的分块")
    @RequirePerm("kb:doc:list")
    @GetMapping("/{id}/chunks")
    public Result<List<KbChunk>> chunks(@PathVariable Long id) {
        return Result.ok(kbDocumentService.chunks(id));
    }
}
