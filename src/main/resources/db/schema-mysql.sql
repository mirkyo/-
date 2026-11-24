-- ============================================================
--  知微 · 业务表（MySQL）
--  说明：
--   1. 只建业务表。框架三张表（agentx_session / agentx_conversation /
--      agentx_pause_state）由框架自己 CREATE TABLE IF NOT EXISTS 建，不要手工干预。
--   2. 可重复执行，不删已有数据。
-- ============================================================
CREATE DATABASE IF NOT EXISTS `ziwei_kb` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `ziwei_kb`;

-- ---------- 租户 ----------
CREATE TABLE IF NOT EXISTS `kb_tenant` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        varchar(128) NOT NULL COMMENT '租户名称',
  `code`        varchar(64)  NOT NULL COMMENT '租户标识',
  `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '0启用 1禁用',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户';

-- ---------- 用户 ----------
CREATE TABLE IF NOT EXISTS `kb_user` (
  `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`       bigint       NOT NULL COMMENT '所属租户',
  `username`        varchar(64)  NOT NULL COMMENT '用户名',
  `password`        varchar(128) NOT NULL COMMENT '密码（bcrypt）',
  `nickname`        varchar(64)  DEFAULT '' COMMENT '昵称',
  `email`           varchar(128) DEFAULT '' COMMENT '邮箱',
  `avatar`          varchar(255) DEFAULT '' COMMENT '头像URL',
  `status`          tinyint      NOT NULL DEFAULT 0 COMMENT '0启用 1禁用',
  `role`            varchar(16)  NOT NULL DEFAULT 'USER' COMMENT 'SUPER_ADMIN/ADMIN/USER',
  `real_name`       varchar(64)  DEFAULT '' COMMENT '真实姓名',
  `fail_count`      int          NOT NULL DEFAULT 0 COMMENT '登录失败次数',
  `lock_until`      datetime     DEFAULT NULL COMMENT '锁定截止时间',
  `last_login_at`   datetime     DEFAULT NULL COMMENT '最近登录时间',
  `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_user_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户';

-- ---------- 智能体：已取消 ----------
-- 智能体广场去掉了，平台只有一个助手，配置在 application.yml 的 app.assistant.*；
-- 技能由「已启用」状态全局自动挂载，不需要按智能体挑。所以这里不再有 kb_agent 表。

-- ---------- 知识库 ----------
CREATE TABLE IF NOT EXISTS `kb_knowledge_base` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`   bigint       NOT NULL COMMENT '所属租户',
  `user_id`     bigint       NOT NULL COMMENT '所属用户',
  `name`        varchar(128) NOT NULL COMMENT '知识库名称',
  `description` varchar(512) DEFAULT '' COMMENT '描述',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_user_name` (`tenant_id`, `user_id`, `name`),
  KEY `idx_kb_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库';

-- ---------- 知识库文档 ----------
CREATE TABLE IF NOT EXISTS `kb_knowledge_document` (
  `id`                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`         bigint        NOT NULL COMMENT '所属租户',
  `knowledge_base_id` bigint        NOT NULL COMMENT '所属知识库',
  `file_name`         varchar(255)  NOT NULL COMMENT '原始文件名',
  `file_type`         varchar(16)   DEFAULT '' COMMENT '扩展名',
  `file_size`         bigint        DEFAULT 0 COMMENT '字节',
  `document_id`       varchar(128)  NOT NULL COMMENT '内容hash（幂等依据）',
  `parser_type`       varchar(16)   NOT NULL DEFAULT 'TIKA' COMMENT 'TIKA/MINERU',
  `splitter_type`     varchar(16)   NOT NULL DEFAULT 'RECURSIVE' COMMENT 'SLIDING/RECURSIVE/HEADING',
  `chunk_size`        int           DEFAULT 500,
  `overlap`           int           DEFAULT 50,
  `max_parent_size`   int           DEFAULT 5000,
  `chunk_count`       int           DEFAULT 0,
  `parent_child`      tinyint       NOT NULL DEFAULT 0,
  `status`            varchar(16)   NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
  `error_msg`         varchar(5000) DEFAULT NULL,
  `create_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`           tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kb_doc` (`knowledge_base_id`, `document_id`),
  KEY `idx_doc_document_id` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='知识库文档';

-- ---------- 会话（把 conversation 与智能体/知识库绑起来） ----------
CREATE TABLE IF NOT EXISTS `kb_conversation` (
  `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`         bigint       NOT NULL COMMENT '所属租户',
  `user_id`           bigint       NOT NULL COMMENT '所属用户',
  `knowledge_base_ids` varchar(512) DEFAULT '' COMMENT '本会话检索范围（逗号分隔的库 id）；空=全部',
  `conversation_id`   varchar(100) NOT NULL COMMENT '会话ID（框架侧一致）',
  `title`             varchar(255) DEFAULT '' COMMENT '会话标题',
  `message_count`     int          NOT NULL DEFAULT 0,
  `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`),
  KEY `idx_conv_user` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会话';

-- ---------- 技能（照抄 aicube-robot 的 agentx_skill） ----------
CREATE TABLE IF NOT EXISTS `agentx_skill` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `name`        varchar(128) NOT NULL COMMENT '技能名（= 目录名）',
  `skill_path`  varchar(255) NOT NULL COMMENT '相对路径（= 目录名，运行时现拼）',
  `description` varchar(1024) DEFAULT '' COMMENT '简介',
  `enabled`     tinyint      NOT NULL DEFAULT 1,
  `file_name`   varchar(255) DEFAULT NULL COMMENT '上传的 zip 原名',
  `created_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='技能';
