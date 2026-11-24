package com.ziwei.zw.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ziwei.zw.rag.domain.KnowledgeDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /** 物理删单文档。同理，{@code uk_kb_doc} 会挡住逻辑删除后的同内容重传。 */
    @Delete("DELETE FROM kb_knowledge_document WHERE knowledge_base_id = #{kbId} AND document_id = #{documentId}")
    int deletePhysically(@Param("kbId") Long kbId, @Param("documentId") String documentId);

    @Delete("DELETE FROM kb_knowledge_document WHERE knowledge_base_id = #{kbId}")
    int deletePhysicallyByKbId(@Param("kbId") Long kbId);
}
