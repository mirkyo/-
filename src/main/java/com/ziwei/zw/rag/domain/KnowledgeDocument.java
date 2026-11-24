package com.ziwei.zw.rag.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ziwei.zw.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档。
 *
 * <p>{@code documentId} 是**内容 SHA-256**，同一份内容重复上传天然幂等（入库时先删后插）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long knowledgeBaseId;

    private String fileName;

    private String fileType;

    private Long fileSize;

    /** 内容 hash，幂等依据 */
    private String documentId;

    /** TIKA / MINERU */
    private String parserType;

    /** SLIDING / RECURSIVE / HEADING */
    private String splitterType;

    private Integer chunkSize;

    private Integer overlap;

    private Integer maxParentSize;

    private Integer chunkCount;

    private Integer parentChild;

    /** SUCCESS / FAILED */
    private String status;

    private String errorMsg;
}
