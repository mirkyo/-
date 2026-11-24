package com.ziwei.zw.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.ziwei.zw.common.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * MyBatis-Plus 配置：租户拦截器 + 分页 + 创建/更新时间自动填充。
 *
 * <p>租户拦截器会自动给带 {@code tenant_id} 的表拼 {@code tenant_id = ?}，
 * 这样业务代码里不用每个查询手写租户条件。
 * 没带租户维度的表（框架表 + 技能表 + 租户表本身）在这里跳过。
 */
@Configuration
public class MybatisPlusConfig {

    /** 不带 tenant_id 的表，交给拦截器跳过。 */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "kb_tenant",
            "agentx_skill",
            "agentx_session",
            "agentx_conversation",
            "agentx_pause_state"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 已去掉多租户：不再安装 TenantLineInnerInterceptor
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
