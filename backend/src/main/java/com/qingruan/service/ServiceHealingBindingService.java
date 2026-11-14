package com.qingruan.service;

import com.qingruan.common.result.PageResult;
import com.qingruan.vo.BoundHealingRuleInfo;
import com.qingruan.vo.ServiceHealingBindingCreateRequest;
import com.qingruan.vo.ServiceHealingBindingInfo;

public interface ServiceHealingBindingService {

    /**
     * 为某服务绑定自愈规则（默认启用监听）
     *
     * @param request 绑定请求
     * @return 绑定后的信息
     */
    ServiceHealingBindingInfo createBinding(ServiceHealingBindingCreateRequest request);

    /**
     * 分页查询某服务（指定监控配置）已绑定的自愈规则列表。
     * <p>
     * 以绑定关系的优先级（priority 降序）排序后分页，每项携带绑定维度字段
     * （rule_id / bind_id）与规则本身信息（BoundHealingRuleInfo）。
     *
     * @param serverCode 服务器编码
     * @param configId   监控配置ID
     * @param pageNum    页码，从 1 开始（可选，默认 1）
     * @param pageSize   每页数量（可选，默认 100）
     * @return 分页结果，list 为已绑定的自愈规则信息
     */
    PageResult<BoundHealingRuleInfo> listBoundRules(String serverCode, Long configId, Integer pageNum, Integer pageSize);

    /**
     * 解除服务与规则的绑定（按绑定记录ID物理删除）
     *
     * @param id 绑定记录主键ID
     */
    void deleteBinding(Long id);

    /**
     * 启停某绑定记录的监听状态
     *
     * @param id     绑定记录主键ID
     * @param status 目标状态：0-停用 / 1-启用
     */
    void toggleBindingStatus(Long id, Integer status);
}
