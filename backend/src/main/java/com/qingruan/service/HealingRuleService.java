package com.qingruan.service;

import com.qingruan.common.result.PageResult;
import com.qingruan.entity.HealingRule;
import com.qingruan.vo.HealingRuleCreateRequest;
import com.qingruan.vo.HealingRuleInfo;
import com.qingruan.vo.HealingRuleStatusRequest;
import com.qingruan.vo.HealingRuleUpdateRequest;

public interface HealingRuleService {

    /**
     * 新增自愈规则（默认启用）
     *
     * @param request 创建请求
     * @return 创建后的规则信息
     */
    HealingRuleInfo createHealingRule(HealingRuleCreateRequest request);

    /**
     * 分页查询自愈规则列表，支持按名称模糊搜索与状态筛选
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页数量
     * @param ruleName 规则名称模糊关键字，null 表示不筛选
     * @param status   状态 0-停用 1-启用，null 表示不筛选
     * @return 分页结果
     */
    PageResult<HealingRuleInfo> listHealingRules(Integer pageNum, Integer pageSize, String ruleName, Integer status);

    /**
     * 修改自愈规则（部分更新，仅更新请求中传入的字段）
     *
     * @param id      规则主键ID
     * @param request 修改请求
     * @return 更新后的规则信息
     */
    HealingRuleInfo updateHealingRule(Long id, HealingRuleUpdateRequest request);

    /**
     * 删除自愈规则（物理删除，schema 无 is_deleted 字段）
     *
     * @param id 规则主键ID
     */
    void deleteHealingRule(Long id);

    /**
     * 启停自愈规则（更新 status 列：0-停用 1-启用）
     *
     * @param id     规则主键ID
     * @param status 目标状态 0/1
     */
    void toggleHealingRuleStatus(Long id, Integer status);

    /**
     * 将自愈规则实体转换为对外信息（解析 trigger_config 为对象）
     *
     * @param rule 规则实体
     * @return 对外信息
     */
    HealingRuleInfo toInfo(HealingRule rule);
}
