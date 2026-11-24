package com.ziwei.zw.session;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 框架三张会话表的清理。
 *
 * <p>框架自己建表、自己写，没有对外暴露"删除会话"的能力，所以这里直接用 SQL 清；
 * 不同步清掉的话，用同一个 conversationId 重开会话会读到上一轮的旧消息。
 */
@Mapper
public interface SessionAdminMapper {

    @Delete("DELETE FROM agentx_session WHERE conversation_id = #{cid}")
    int deleteSession(@Param("cid") String conversationId);

    @Delete("DELETE FROM agentx_conversation WHERE conversation_id = #{cid}")
    int deleteConversation(@Param("cid") String conversationId);

    @Delete("DELETE FROM agentx_pause_state WHERE conversation_id = #{cid}")
    int deletePauseState(@Param("cid") String conversationId);
}
