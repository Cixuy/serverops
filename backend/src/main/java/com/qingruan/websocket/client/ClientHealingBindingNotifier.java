package com.qingruan.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingruan.entity.HealingRule;
import com.qingruan.entity.ServiceHealingBinding;
import com.qingruan.mapper.HealingRuleMapper;
import com.qingruan.mapper.ServiceHealingBindingMapper;
import com.qingruan.websocket.client.model.HealingRuleBindingChangeItem;
import com.qingruan.websocket.client.model.HealingRuleBindingChangeMessage;
import com.qingruan.websocket.client.model.RuleSnapshot;
import com.qingruan.websocket.client.session.ClientSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 自愈规则绑定变更通知器：在绑定或其所引用的规则发生变更时，
 * 按 serverCode 向对应客户端会话推送 {@code HEALING_RULE_BINDING_CHANGE} 消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientHealingBindingNotifier {

    /** 单条绑定新增 */
    public static final String ACTION_ADD = "ADD";
    /** 单条绑定（含其规则）移除 */
    public static final String ACTION_REMOVE = "REMOVE";
    /** 单条绑定或其所引用规则发生更新 */
    public static final String ACTION_UPDATE = "UPDATE";

    private final ClientSessionManager clientSessionManager;
    private final ServiceHealingBindingMapper bindingMapper;
    private final HealingRuleMapper healingRuleMapper;
    private final ObjectMapper objectMapper;

    /**
     * 针对单条绑定变更推送（新增/解绑/启停）。
     */
    public void notifyBindingChange(ServiceHealingBinding binding, HealingRule rule, String actionType) {
        HealingRuleBindingChangeItem item = toItem(binding, rule, actionType);
        HealingRuleBindingChangeMessage message = new HealingRuleBindingChangeMessage();
        message.setRequestId(UUID.randomUUID().toString());
        message.setData(List.of(item));
        push(binding.getServerCode(), message);
        log.info("[WS-CLIENT] 推送自愈规则绑定变更 {} (serverCode={}, bindingId={})",
                actionType, binding.getServerCode(), binding.getId());
    }

    /**
     * 规则本身发生变更时，向所有引用该规则的服务端推送（按 serverCode 聚合批量下发）。
     *
     * @param ruleId      发生变更的规则ID
     * @param actionType  ADD / UPDATE / REMOVE
     */
    public void notifyRuleChanged(Long ruleId, String actionType) {
        List<ServiceHealingBinding> bindings = bindingMapper.selectList(
                new LambdaQueryWrapper<ServiceHealingBinding>().eq(ServiceHealingBinding::getRuleId, ruleId));
        if (bindings.isEmpty()) {
            return;
        }
        // 规则已删除时快照取不到，置为 null 即可（客户端按 rule_id 移除）
        HealingRule rule = healingRuleMapper.selectById(ruleId);
        Map<String, List<ServiceHealingBinding>> byServer = bindings.stream()
                .collect(Collectors.groupingBy(ServiceHealingBinding::getServerCode));
        byServer.forEach((serverCode, list) -> {
            List<HealingRuleBindingChangeItem> items = list.stream()
                    .map(b -> toItem(b, rule, actionType))
                    .collect(Collectors.toList());
            HealingRuleBindingChangeMessage message = new HealingRuleBindingChangeMessage();
            message.setRequestId(UUID.randomUUID().toString());
            message.setData(items);
            push(serverCode, message);
        });
        log.info("[WS-CLIENT] 推送自愈规则变更 {} (ruleId={}, 影响服务器数={})",
                actionType, ruleId, byServer.size());
    }

    private void push(String serverCode, HealingRuleBindingChangeMessage message) {
        if (serverCode == null || serverCode.isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(message);
            clientSessionManager.sendToServer(serverCode, payload);
        } catch (Exception e) {
            log.warn("[WS-CLIENT] 序列化自愈规则绑定变更消息失败 (serverCode={}): {}", serverCode, e.getMessage());
        }
    }

    private HealingRuleBindingChangeItem toItem(ServiceHealingBinding binding, HealingRule rule, String actionType) {
        HealingRuleBindingChangeItem item = new HealingRuleBindingChangeItem();
        item.setBindingId(binding.getId());
        item.setServerCode(binding.getServerCode());
        item.setConfigId(binding.getConfigId());
        item.setRuleId(binding.getRuleId());
        item.setPriority(binding.getPriority());
        item.setStatus(binding.getStatus());
        item.setRuleSnapshot(rule != null ? toSnapshot(rule) : null);
        item.setActionType(actionType);
        return item;
    }

    private RuleSnapshot toSnapshot(HealingRule rule) {
        RuleSnapshot snapshot = new RuleSnapshot();
        snapshot.setRuleName(rule.getRuleName());
        // 解析 trigger_config 为普通对象（Map/List），交由 Jackson 序列化，保持为 JSON 对象而非字符串
        Object triggerConfig = null;
        if (rule.getTriggerConfig() != null) {
            try {
                triggerConfig = objectMapper.readValue(rule.getTriggerConfig(), Object.class);
            } catch (Exception e) {
                log.warn("[WS-CLIENT] 解析 trigger_config 失败 (ruleId={}): {}", rule.getId(), e.getMessage());
            }
        }
        snapshot.setTriggerConfig(triggerConfig);
        snapshot.setExecuteCommand(rule.getExecuteCommand());
        snapshot.setTimeoutSeconds(rule.getTimeoutSeconds());
        snapshot.setMaxRetries(rule.getMaxRetries());
        snapshot.setRetryInterval(rule.getRetryInterval());
        return snapshot;
    }
}
