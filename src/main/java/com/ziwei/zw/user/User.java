package com.ziwei.zw.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ziwei.zw.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_user")
public class User extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String username;

    /** bcrypt 哈希，永不返回给前端 */
    @JsonIgnore
    private String password;

    private String nickname;

    private String email;

    private String avatar;

    /** 0启用 1禁用 */
    private Integer status;

    /** SUPER_ADMIN / ADMIN / USER */
    private String role;

    private String realName;

    private Integer failCount;

    private LocalDateTime lockUntil;

    private LocalDateTime lastLoginAt;
}
