package com.qingruan.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.exception.BizException;
import com.qingruan.common.result.PageResult;
import com.qingruan.entity.HealingRule;
import com.qingruan.mapper.HealingRuleMapper;
import com.qingruan.service.HealingRuleService;
import com.qingruan.vo.HealingRuleCreateRequest;
import com.qingruan.vo.HealingRuleInfo;
import com.qingruan.vo.HealingRuleUpdateRequest;
import com.qingruan.websocket.client.ClientHealingBindingNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealingRuleServiceImpl extends ServiceImpl<HealingRuleMapper, HealingRule> implements HealingRuleService {

    private static final List<String> VALID_LOGICS = List.of("AND", "OR");

    private final ClientHealingBindingNotifier notifier;
    private final ObjectMapper objectMapper;

    @Override
    public HealingRuleInfo createHealingRule(HealingRuleCreateRequest request) {
        // rule_name 为唯一约束，先查重避免落到数据库唯一键冲突（500）
        if (count(new LambdaQueryWrapper<HealingRule>().eq(HealingRule::getRuleName, request.getRuleName())) > 0) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        HealingRule rule = new HealingRule();
        rule.setRuleName(request.getRuleName());
        rule.setDescription(request.getDescription());
        // trigger_config 为 JSON 列，实体中以字符串存储；此处做解析与结构校验
        rule.setTriggerConfig(normalizeTriggerConfig(request.getTriggerConfig()));
        rule.setExecuteCommand(request.getExecuteCommand());
        rule.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 60);
        rule.setMaxRetries(request.getMaxRetries() != null ? request.getMaxRetries() : 3);
        rule.setRetryInterval(request.getRetryInterval() != null ? request.getRetryInterval() : 30);
        rule.setStatus(1); // 默认启用
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        save(rule);
        return toInfo(rule);
    }

    @Override
    public HealingRuleInfo toInfo(HealingRule rule) {
        HealingRuleInfo info = new HealingRuleInfo();
        info.setId(rule.getId());
        info.setRuleName(rule.getRuleName());
        info.setDescription(rule.getDescription());
        info.setTriggerConfig(rule.getTriggerConfig() != null ? JSON.parse(rule.getTriggerConfig()) : null);
        info.setExecuteCommand(rule.getExecuteCommand());
        info.setTimeoutSeconds(rule.getTimeoutSeconds());
        info.setMaxRetries(rule.getMaxRetries());
        info.setRetryInterval(rule.getRetryInterval());
        info.setStatus(rule.getStatus());
        info.setCreatedAt(rule.getCreatedAt());
        info.setUpdatedAt(rule.getUpdatedAt());
        return info;
    }

    @Override
    public PageResult<HealingRuleInfo> listHealingRules(Integer pageNum, Integer pageSize, String ruleName, Integer status) {
        Page<HealingRule> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<HealingRule> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(ruleName)) {
            wrapper.like(HealingRule::getRuleName, ruleName);
        }
        if (status != null) {
            wrapper.eq(HealingRule::getStatus, status);
        }
        wrapper.orderByDesc(HealingRule::getCreatedAt);
        IPage<HealingRule> result = page(page, wrapper);
        List<HealingRuleInfo> infos = result.getRecords().stream().map(this::toInfo).collect(Collectors.toList());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), infos);
    }

    @Override
    public HealingRuleInfo updateHealingRule(Long id, HealingRuleUpdateRequest request) {
        HealingRule existing = getById(id);
        if (existing == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }
        // rule_name 唯一：仅当修改了名称且与他人冲突时拦截，避免落到数据库唯一键冲突（500）
        if (StringUtils.hasText(request.getRuleName()) && !request.getRuleName().equals(existing.getRuleName())) {
            long conflict = count(new LambdaQueryWrapper<HealingRule>()
                    .eq(HealingRule::getRuleName, request.getRuleName()).ne(HealingRule::getId, id));
            if (conflict > 0) {
                throw new BizException(SystemCodeEnum.BAD_REQUEST.getCode(), "规则名称已存在");
            }
        }

        // 部分更新：仅 set 请求中传入的非空字段，避免 updateById 把其余字段置空
        LambdaUpdateWrapper<HealingRule> uw = new LambdaUpdateWrapper<>();
        uw.eq(HealingRule::getId, id);
        if (StringUtils.hasText(request.getRuleName())) {
            uw.set(HealingRule::getRuleName, request.getRuleName());
        }
        if (request.getDescription() != null) {
            uw.set(HealingRule::getDescription, request.getDescription());
        }
        if (request.getTriggerConfig() != null) {
            uw.set(HealingRule::getTriggerConfig, normalizeTriggerConfig(request.getTriggerConfig()));
        }
        if (request.getExecuteCommand() != null) {
            uw.set(HealingRule::getExecuteCommand, request.getExecuteCommand());
        }
        if (request.getTimeoutSeconds() != null) {
            uw.set(HealingRule::getTimeoutSeconds, request.getTimeoutSeconds());
        }
        if (request.getMaxRetries() != null) {
            uw.set(HealingRule::getMaxRetries, request.getMaxRetries());
        }
        if (request.getRetryInterval() != null) {
            uw.set(HealingRule::getRetryInterval, request.getRetryInterval());
        }
        uw.set(HealingRule::getUpdatedAt, LocalDateTime.now());
        update(uw);

        // 规则更新：通知所有引用该规则的服务端刷新绑定快照
        notifier.notifyRuleChanged(id, ClientHealingBindingNotifier.ACTION_UPDATE);

        return toInfo(getById(id));
    }

    @Override
    public void deleteHealingRule(Long id) {
        if (getById(id) == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }
        // 物理删除（healing_rule 表无 is_deleted 字段）
        removeById(id);
        // 规则删除：通知所有引用该规则的服务端移除对应绑定（快照置空）
        notifier.notifyRuleChanged(id, ClientHealingBindingNotifier.ACTION_REMOVE);
    }

    @Override
    public void toggleHealingRuleStatus(Long id, Integer status) {
        if (getById(id) == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }
        // 仅更新 status 列，刷新 updated_at
        LambdaUpdateWrapper<HealingRule> uw = new LambdaUpdateWrapper<>();
        uw.eq(HealingRule::getId, id)
                .set(HealingRule::getStatus, status)
                .set(HealingRule::getUpdatedAt, LocalDateTime.now());
        update(uw);
        // 规则启停：通知所有引用该规则的服务端刷新绑定
        notifier.notifyRuleChanged(id, ClientHealingBindingNotifier.ACTION_UPDATE);
    }

    /**
     * 解析并校验 trigger_config（兼容表达式树与扁平单条件两种形态）：
     * <ul>
     *   <li>允许入参为 JSON 对象或 JSON 字符串（自动解析，避免二次转义）</li>
     *   <li>组合节点：含 logic(AND/OR) 与非空 conditions 数组（元素可继续嵌套）</li>
     *   <li>叶子节点：支持显式带 type 写法，或扁平单条件写法 { metric, operator, threshold/value }</li>
     *   <li>解析或校验失败时抛出携带可读信息（含路径）的 BizException，并记录日志</li>
     * </ul>
     * 校验通过后序列化为规整的 JSON 字符串入库。
     */
    private String normalizeTriggerConfig(Object raw) {
        if (raw == null) {
            return null;
        }
        Object parsed;
        if (raw instanceof String s) {
            if (s.isBlank()) {
                return null;
            }
            try {
                parsed = objectMapper.readValue(s, Object.class);
            } catch (Exception e) {
                throw bad("trigger_config 不是合法的 JSON: " + e.getMessage());
            }
        } else {
            parsed = raw;
        }

        if (!(parsed instanceof Map)) {
            throw bad("trigger_config 顶层必须为 JSON 对象");
        }
        validateTriggerNode(parsed, "");
        try {
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            throw bad("trigger_config 序列化失败: " + e.getMessage());
        }
    }

    /**
     * 递归校验触发条件表达式树，兼容接口约定的两种形态：
     * <ul>
     *   <li>组合节点：含 logic(AND/OR) 与 conditions 数组（元素可继续嵌套）</li>
     *   <li>叶子节点（两种写法）：
     *     <ul>
     *       <li>显式带 type：{ type: METRIC/SERVICE_STATUS/LOG_MATCH, ... }</li>
     *       <li>扁平单条件（多见于简单规则）：{ metric, operator, threshold/value, duration }（无 type/logic/conditions）</li>
     *     </ul>
     *   </li>
     * </ul>
     * path 用于精确定位错误。
     */
    @SuppressWarnings("unchecked")
    private void validateTriggerNode(Object node, String path) {
        if (!(node instanceof Map)) {
            throw bad("trigger_config" + path + " 节点必须为 JSON 对象");
        }
        Map<String, Object> m = (Map<String, Object>) node;
        boolean isGroup = m.containsKey("logic") || m.containsKey("conditions");

        Object type = m.get("type");
        if (isGroup) {
            // 组合节点
            Object logic = m.get("logic");
            if (logic == null || !VALID_LOGICS.contains(logic.toString().toUpperCase())) {
                throw bad("trigger_config" + path + ".logic 非法，必须为 AND 或 OR");
            }
            Object conditions = m.get("conditions");
            if (!(conditions instanceof List) || ((List<?>) conditions).isEmpty()) {
                throw bad("trigger_config" + path + ".conditions 必须为非空数组");
            }
            int i = 0;
            for (Object child : (List<?>) conditions) {
                validateTriggerNode(child, path + ".conditions[" + i + "]");
                i++;
            }
            return;
        }

        if (type != null) {
            // 显式带 type 的叶子节点
            switch (type.toString()) {
                case "METRIC":
                    require(m, "metric", path);
                    require(m, "operator", path);
                    requireEither(m, "value", "threshold", path);
                    break;
                case "SERVICE_STATUS":
                    require(m, "operator", path);
                    requireEither(m, "value", "threshold", path);
                    break;
                case "LOG_MATCH":
                    require(m, "log_file_path", path);
                    require(m, "regex_pattern", path);
                    break;
                default:
                    throw bad("trigger_config" + path + " 未知 type: " + type);
            }
            return;
        }

        // 扁平单条件写法（无 type / logic / conditions）：视为 METRIC 条件
        if (m.containsKey("metric")) {
            require(m, "metric", path);
            require(m, "operator", path);
            requireEither(m, "value", "threshold", path);
            return;
        }

        throw bad("trigger_config" + path + " 既不是组合条件(logic/conditions)也不是合法的条件节点(metric/type)");
    }

    private void require(Map<String, Object> m, String field, String path) {
        if (m.get(field) == null) {
            throw bad("trigger_config" + path + " 缺少必填字段: " + field);
        }
    }

    private void requireEither(Map<String, Object> m, String f1, String f2, String path) {
        if (m.get(f1) == null && m.get(f2) == null) {
            throw bad("trigger_config" + path + " 缺少必填字段: " + f1 + " 或 " + f2);
        }
    }

    private BizException bad(String msg) {
        log.error("[HEALING-RULE] {}", msg);
        return new BizException(SystemCodeEnum.BAD_REQUEST.getCode(), msg);
    }
}
