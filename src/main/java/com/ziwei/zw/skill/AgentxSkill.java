package com.ziwei.zw.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能记录（对应表 agentx_skill）。
 *
 * <p>技能是**平台级**的（所有租户共用一份 skills 目录），所以没有 tenant_id。
 * 需要按租户隔离技能时再加列 + 加进 MybatisPlusConfig 的忽略名单调整。
 */
@Data
@TableName("agentx_skill")
public class AgentxSkill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 相对路径（= 目录名）。运行时用 skillsDir + name 现拼，不依赖这里存的值 */
    private String skillPath;

    private String description;

    private Integer enabled;

    private String fileName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
