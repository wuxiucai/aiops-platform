package com.aiops.module.kb.service;

import com.aiops.common.BizException;
import com.aiops.module.kb.entity.KbChunk;
import com.aiops.module.kb.entity.KbDocument;
import com.aiops.module.kb.mapper.KbChunkMapper;
import com.aiops.module.kb.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档。文档与分块在同一事务内写入：
 * 此前在 Controller 中逐条执行，分块插入中途失败会留下"文档在、分块缺一半、chunk_count 不准"的状态。
 * 嵌入不在本期范围：embedding_status 仅作占位（none）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbDocumentService {

    /** 单个 chunk 字符数（中文按字符近似） */
    static final int CHUNK_SIZE = 500;

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;

    public Page<KbDocument> page(long current, long size, String keyword) {
        return kbDocumentMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<KbDocument>()
                        .like(keyword != null && !keyword.isBlank(), KbDocument::getTitle, keyword)
                        .orderByDesc(KbDocument::getId));
    }

    public KbDocument detail(Long id) {
        KbDocument d = kbDocumentMapper.selectById(id);
        if (d == null || (d.getDeleted() != null && d.getDeleted() == 1)) {
            throw new BizException("文档不存在");
        }
        return d;
    }

    /** 新建（手输或上传）并分块，返回写入后的文档（含 id / chunkCount） */
    @Transactional
    public KbDocument create(KbDocument doc) {
        if (doc.getTitle() == null || doc.getTitle().isBlank()) {
            throw new BizException("title 必填");
        }
        if (doc.getContent() == null || doc.getContent().isBlank()) {
            throw new BizException("content 必填");
        }
        doc.setId(null);
        doc.setDeleted(0);
        doc.setCreateTime(LocalDateTime.now());
        doc.setUpdateTime(LocalDateTime.now());
        if (doc.getEmbeddingStatus() == null) doc.setEmbeddingStatus("none");
        if (doc.getDocType() == null) doc.setDocType("txt");
        if (doc.getSource() == null) doc.setSource("manual");
        kbDocumentMapper.insert(doc);
        doc.setChunkCount(rebuildChunks(doc.getId(), doc.getContent()));
        kbDocumentMapper.updateById(doc);
        return doc;
    }

    /** 编辑；content 变化时重做分块。返回当前分块数 */
    @Transactional
    public int update(KbDocument body) {
        if (body.getId() == null) throw new BizException("id 必填");
        KbDocument exist = kbDocumentMapper.selectById(body.getId());
        if (exist == null) throw new BizException("文档不存在");

        body.setUpdateTime(LocalDateTime.now());
        boolean contentChanged = body.getContent() != null && !body.getContent().equals(exist.getContent());
        kbDocumentMapper.updateById(body);
        if (!contentChanged) {
            return exist.getChunkCount() == null ? 0 : exist.getChunkCount();
        }
        int chunks = rebuildChunks(body.getId(), body.getContent());
        KbDocument upd = new KbDocument();
        upd.setId(body.getId());
        upd.setChunkCount(chunks);
        kbDocumentMapper.updateById(upd);
        return chunks;
    }

    /** 软删文档，物理删分块 */
    @Transactional
    public void delete(Long id) {
        KbDocument exist = kbDocumentMapper.selectById(id);
        if (exist == null) throw new BizException("文档不存在");
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, id));
        kbDocumentMapper.deleteById(id);
    }

    public List<KbChunk> chunks(Long id) {
        return kbChunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, id)
                .orderByAsc(KbChunk::getChunkIndex));
    }

    /** 简单等长分块：按 CHUNK_SIZE 字符切片 */
    static List<String> split(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;
        for (int i = 0; i < content.length(); i += CHUNK_SIZE) {
            out.add(content.substring(i, Math.min(i + CHUNK_SIZE, content.length())));
        }
        return out;
    }

    private int rebuildChunks(Long docId, String content) {
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, docId));
        List<String> parts = split(content);
        for (int idx = 0; idx < parts.size(); idx++) {
            KbChunk c = new KbChunk();
            c.setDocumentId(docId);
            c.setChunkIndex(idx);
            c.setContent(parts.get(idx));
            c.setTokenCount(parts.get(idx).length());
            c.setCreateTime(LocalDateTime.now());
            kbChunkMapper.insert(c);  // 逐条 insert，毕设规模足够
        }
        log.info("[kb] document={} 重建分块 {} 片", docId, parts.size());
        return parts.size();
    }
}
