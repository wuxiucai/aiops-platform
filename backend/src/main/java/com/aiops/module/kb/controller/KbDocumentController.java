package com.aiops.module.kb.controller;

import com.aiops.common.BizException;
import com.aiops.common.Result;
import com.aiops.module.kb.entity.KbChunk;
import com.aiops.module.kb.entity.KbDocument;
import com.aiops.module.kb.mapper.KbChunkMapper;
import com.aiops.module.kb.mapper.KbDocumentMapper;
import com.aiops.security.RequirePerm;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 - 文档管理（S2 kb/Document）
 * 提供：列表 / 创建（手输 or 上传文件）/ 编辑 / 删除 / 分块。
 * 嵌入不在本期范围：embedding_status 仅作占位（none）。
 */
@Slf4j
@Tag(name = "知识库 文档")
@RestController
@RequestMapping("/api/kb/document")
@RequiredArgsConstructor
public class KbDocumentController {

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;

    /** 单个 chunk 字符数（中文按字符近似） */
    private static final int CHUNK_SIZE = 500;

    @Operation(summary = "分页列表")
    @RequirePerm("kb:doc:list")
    @GetMapping
    public Result<Page<KbDocument>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String keyword) {
        return Result.ok(kbDocumentMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<KbDocument>()
                        .like(keyword != null && !keyword.isBlank(), KbDocument::getTitle, keyword)
                        .orderByDesc(KbDocument::getId)));
    }

    @Operation(summary = "详情（含 content）")
    @RequirePerm("kb:doc:list")
    @GetMapping("/{id}")
    public Result<KbDocument> detail(@PathVariable Long id) {
        KbDocument d = kbDocumentMapper.selectById(id);
        if (d == null || (d.getDeleted() != null && d.getDeleted() == 1)) {
            throw new BizException("文档不存在");
        }
        return Result.ok(d);
    }

    @Operation(summary = "新建（手输）")
    @RequirePerm("kb:doc:list")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody KbDocument body) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            throw new BizException("title 必填");
        }
        if (body.getContent() == null || body.getContent().isBlank()) {
            throw new BizException("content 必填");
        }
        body.setId(null);
        body.setDeleted(0);
        body.setCreateTime(LocalDateTime.now());
        body.setUpdateTime(LocalDateTime.now());
        if (body.getEmbeddingStatus() == null) body.setEmbeddingStatus("none");
        if (body.getDocType() == null) body.setDocType("txt");
        if (body.getSource() == null) body.setSource("manual");
        kbDocumentMapper.insert(body);
        int chunks = rebuildChunks(body.getId(), body.getContent());
        body.setChunkCount(chunks);
        kbDocumentMapper.updateById(body);
        Map<String, Object> r = new HashMap<>();
        r.put("id", body.getId());
        r.put("chunkCount", chunks);
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
        doc.setEmbeddingStatus("none");
        doc.setDeleted(0);
        doc.setCreateTime(LocalDateTime.now());
        doc.setUpdateTime(LocalDateTime.now());
        kbDocumentMapper.insert(doc);
        int chunks = rebuildChunks(doc.getId(), content);
        doc.setChunkCount(chunks);
        kbDocumentMapper.updateById(doc);

        Map<String, Object> r = new HashMap<>();
        r.put("id", doc.getId());
        r.put("title", finalTitle);
        r.put("chunkCount", chunks);
        return Result.ok(r);
    }

    @Operation(summary = "编辑（替换 content 会重做分块）")
    @RequirePerm("kb:doc:list")
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody KbDocument body) {
        if (body.getId() == null) throw new BizException("id 必填");
        KbDocument exist = kbDocumentMapper.selectById(body.getId());
        if (exist == null) throw new BizException("文档不存在");

        body.setUpdateTime(LocalDateTime.now());
        boolean contentChanged = body.getContent() != null && !body.getContent().equals(exist.getContent());
        kbDocumentMapper.updateById(body);
        int chunks = exist.getChunkCount() == null ? 0 : exist.getChunkCount();
        if (contentChanged) {
            chunks = rebuildChunks(body.getId(), body.getContent());
            KbDocument upd = new KbDocument();
            upd.setId(body.getId());
            upd.setChunkCount(chunks);
            kbDocumentMapper.updateById(upd);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("id", body.getId());
        r.put("chunkCount", chunks);
        return Result.ok(r);
    }

    @Operation(summary = "删除（软删）")
    @RequirePerm("kb:doc:list")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        KbDocument exist = kbDocumentMapper.selectById(id);
        if (exist == null) throw new BizException("文档不存在");
        // 先删 chunk
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, id));
        kbDocumentMapper.deleteById(id);
        return Result.ok();
    }

    @Operation(summary = "查看某文档的分块")
    @RequirePerm("kb:doc:list")
    @GetMapping("/{id}/chunks")
    public Result<List<KbChunk>> chunks(@PathVariable Long id) {
        return Result.ok(kbChunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getDocumentId, id)
                        .orderByAsc(KbChunk::getChunkIndex)));
    }

    /** 简单等长分块：按 CHUNK_SIZE 字符切片 */
    private int rebuildChunks(Long docId, String content) {
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, docId));
        if (content == null || content.isBlank()) return 0;
        List<KbChunk> buf = new ArrayList<>();
        int i = 0;
        int total = content.length();
        while (i < total) {
            int end = Math.min(i + CHUNK_SIZE, total);
            KbChunk c = new KbChunk();
            c.setDocumentId(docId);
            c.setChunkIndex(buf.size());
            c.setContent(content.substring(i, end));
            c.setTokenCount(end - i);
            c.setCreateTime(LocalDateTime.now());
            buf.add(c);
            i = end;
        }
        // 逐条 insert，毕设规模足够
        for (KbChunk c : buf) kbChunkMapper.insert(c);
        log.info("[kb] document={} 重建分块 {} 片", docId, buf.size());
        return buf.size();
    }
}
