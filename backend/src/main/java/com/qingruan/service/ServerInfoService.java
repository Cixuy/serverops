package com.qingruan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qingruan.common.result.PageResult;
import com.qingruan.entity.ServerInfo;

public interface ServerInfoService extends IService<ServerInfo> {

    /**
     * 分页查询服务器列表，支持按状态与关键字（主机名/IP）筛选
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页数量
     * @param status   状态：1-在线 0-离线，null 表示不筛选
     * @param keyword  关键字，匹配 hostname 或 ip_address，null/空表示不筛选
     * @return 分页结果
     */
    PageResult<ServerInfo> getServerList(Integer pageNum, Integer pageSize, Integer status, String keyword);

    /**
     * 根据主键ID获取服务器详情
     *
     * @param id 服务器主键ID
     * @return 服务器信息，不存在时返回 null
     */
    ServerInfo getServerDetail(Long id);

    /**
     * 删除服务器：逻辑删除 MySQL 记录，并清理关联的 InfluxDB 时序数据
     *
     * @param id 服务器主键ID
     * @return true-删除成功（记录存在），false-记录不存在
     */
    boolean deleteServer(Long id);
}
