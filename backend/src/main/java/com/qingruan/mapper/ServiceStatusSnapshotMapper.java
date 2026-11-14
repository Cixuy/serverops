package com.qingruan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qingruan.entity.ServiceStatusSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 服务状态快照表 Mapper
 */
@Mapper
public interface ServiceStatusSnapshotMapper extends BaseMapper<ServiceStatusSnapshot> {
}
