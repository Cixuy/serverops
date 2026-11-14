package com.qingruan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qingruan.entity.Server;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 客户端服务器信息 Mapper（MySQL: server_info）
 */
@Mapper
public interface ServerMapper extends BaseMapper<Server> {

    /**
     * 根据 hostname + ipAddress 查询已注册服务器（用于注册幂等）
     */
    List<Server> selectByHostAndIp(@Param("hostname") String hostname,
                                   @Param("ipAddress") String ipAddress);
}
