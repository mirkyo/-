package com.ziwei.zw.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 物理删除。
     *
     * <p>username 上有唯一键 {@code uk_username}，逻辑删除会一直占着这个名字，
     * 导致同名用户再也建不回来，所以删用户走物理删。
     */
    @Delete("DELETE FROM kb_user WHERE id = #{id}")
    int deletePhysically(@Param("id") Long id);
}
