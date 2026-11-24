package com.ziwei.zw.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话映射（对应 kb_conversation）。
 *
 * <p>只存「会话 ↔ 智能体/知识库」与标题、轮次；消息明细在框架的 agentx_session 里。
 */
@Data
@TableName("kb_conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    /** 本会话检索范围（逗号分隔的知识库 id）；空 = 全部 */
    private String knowledgeBaseIds;

    private String conversationId;

    private String title;

    private Integer messageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
