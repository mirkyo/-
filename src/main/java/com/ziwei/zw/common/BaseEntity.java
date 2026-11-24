package com.ziwei.zw.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务实体基类：创建/更新时间 + 逻辑删除。
 *
 * <p>注意：{@code tenant_id} 不放在这里，由各实体显式声明，便于看清哪些表带租户维度。
 */
@Data
public abstract class BaseEntity implements Serializable {

    /** 已去掉多租户：固定 1，保留该列只为兼容历史数据 */
    @com.baomidou.mybatisplus.annotation.TableField("tenant_id")
    private Long tenantId = 1L;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
