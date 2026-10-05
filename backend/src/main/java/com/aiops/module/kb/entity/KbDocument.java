package com.aiops.module.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库 - 文档（kb_document）
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("title")
    private String title;

    /** txt/md/docx 等扩展名 */
    @TableField("doc_type")
    private String docType;

    /** 全文（kb_chunk 由它分块拷贝生成） */
    @TableField("content")
    private String content;

    /** 来源：manual / 文件名等 */
    @TableField("source")
    private String source;

    /** JSON array string */
    @TableField("tags")
    private String tags;

    @TableField("chunk_count")
    private Integer chunkCount;

    /** none | pending | done */
    @TableField("embedding_status")
    private String embeddingStatus;

    @TableField("creator")
    private String creator;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("deleted")
    private Integer deleted;
}
