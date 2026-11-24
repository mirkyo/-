package com.ziwei.zw.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ziwei.zw.rag.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 物理删除。
     *
     * <p>知识库名带唯一键 {@code uk_tenant_user_name}，逻辑删除会占着键位导致同名重建失败，
     * 所以删除知识库走物理删。
     */
    @Delete("DELETE FROM kb_knowledge_base WHERE id = #{id}")
    int deletePhysically(@Param("id") Long id);
}
