package com.qingruan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.exception.BizException;
import com.qingruan.entity.HealingRule;
import com.qingruan.entity.MonitorServiceConfig;
import com.qingruan.entity.ServiceHealingBinding;
import com.qingruan.mapper.HealingRuleMapper;
import com.qingruan.mapper.MonitorServiceConfigMapper;
import com.qingruan.mapper.ServiceHealingBindingMapper;
import com.qingruan.common.result.PageResult;
import com.qingruan.service.HealingRuleService;
import com.qingruan.service.ServiceHealingBindingService;
import com.qingruan.vo.BoundHealingRuleInfo;
import com.qingruan.vo.HealingRuleInfo;
import com.qingruan.vo.ServiceHealingBindingCreateRequest;
import com.qingruan.vo.ServiceHealingBindingInfo;
import com.qingruan.websocket.client.ClientHealingBindingNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ServiceHealingBindingServiceImpl extends ServiceImpl<ServiceHealingBindingMapper, ServiceHealingBinding> implements ServiceHealingBindingService {

    private final HealingRuleMapper healingRuleMapper;
    private final MonitorServiceConfigMapper monitorServiceConfigMapper;
    private final HealingRuleService healingRuleService;
    private final ClientHealingBindingNotifier notifier;

    @Override
    public ServiceHealingBindingInfo createBinding(ServiceHealingBindingCreateRequest request) {
        // 同一 (serverCode, configId, ruleId) 不允许重复绑定
        long exist = count(new LambdaQueryWrapper<ServiceHealingBinding>()
                .eq(ServiceHealingBinding::getServerCode, request.getServerCode())
                .eq(ServiceHealingBinding::getConfigId, request.getConfigId())
                .eq(ServiceHealingBinding::getRuleId, request.getRuleId()));
        if (exist > 0) {
            throw new BizException(SystemCodeEnum.BAD_REQUEST.getCode(), "该服务已绑定此规则");
        }
        HealingRule rule = healingRuleMapper.selectById(request.getRuleId());
        if (rule == null) {
            throw new BizException(SystemCodeEnum.BAD_REQUEST.getCode(), "自愈规则不存在");
        }
        MonitorServiceConfig config = monitorServiceConfigMapper.selectById(request.getConfigId());
        if (config == null) {
            throw new BizException(SystemCodeEnum.BAD_REQUEST.getCode(), "监控配置不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        ServiceHealingBinding binding = new ServiceHealingBinding();
        binding.setServerCode(request.getServerCode());
        binding.setConfigId(request.getConfigId());
        binding.setRuleId(request.getRuleId());
        binding.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        binding.setStatus(1); // 默认启用监听
        binding.setCreatedAt(now);
        binding.setUpdatedAt(now);
        save(binding);

        // 绑定新增：通知对应客户端（serverCode 维度）
        notifier.notifyBindingChange(binding, rule, ClientHealingBindingNotifier.ACTION_ADD);

        return toInfo(binding, config.getServiceName(), rule.getRuleName());
    }

    private ServiceHealingBindingInfo toInfo(ServiceHealingBinding binding, String serviceName, String ruleName) {
        ServiceHealingBindingInfo info = new ServiceHealingBindingInfo();
        info.setId(binding.getId());
        info.setServerCode(binding.getServerCode());
        info.setConfigId(binding.getConfigId());
        info.setServiceName(serviceName);
        info.setRuleId(binding.getRuleId());
        info.setRuleName(ruleName);
        info.setPriority(binding.getPriority());
        info.setStatus(binding.getStatus());
        info.setCreatedAt(binding.getCreatedAt());
        return info;
    }

    @Override
    public PageResult<BoundHealingRuleInfo> listBoundRules(String serverCode, Long configId, Integer pageNum, Integer pageSize) {
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 100;
        }
        // 1. 取该服务配置下的所有绑定（按优先级降序），得到有序的 ruleId 列表
        LambdaQueryWrapper<ServiceHealingBinding> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ServiceHealingBinding::getServerCode, serverCode)
                .eq(ServiceHealingBinding::getConfigId, configId)
                .orderByDesc(ServiceHealingBinding::getPriority);
        List<ServiceHealingBinding> bindings = list(wrapper);
        if (bindings.isEmpty()) {
            return PageResult.of(pageNum, pageSize, 0L, List.of());
        }

        // 2. 按优先级顺序分页（保持优先级排序）
        List<Long> ruleIds = bindings.stream()
                .map(ServiceHealingBinding::getRuleId)
                .collect(Collectors.toList());
        long total = ruleIds.size();
        int from = Math.min((pageNum - 1) * pageSize, ruleIds.size());
        int to = Math.min(from + pageSize, ruleIds.size());
        List<Long> pageIds = ruleIds.subList(from, to);
        if (pageIds.isEmpty()) {
            return PageResult.of(pageNum, pageSize, total, List.of());
        }

        // 3. 批量查询规则，并按 (serverCode, configId) 唯一映射绑定记录
        List<HealingRule> rules = healingRuleMapper.selectList(
                new LambdaQueryWrapper<HealingRule>().in(HealingRule::getId, pageIds));
        Map<Long, HealingRule> ruleMap = rules.stream()
                .collect(Collectors.toMap(HealingRule::getId, r -> r, (a, b) -> a));
        Map<Long, ServiceHealingBinding> bindingMap = bindings.stream()
                .collect(Collectors.toMap(ServiceHealingBinding::getRuleId, b -> b, (a, b) -> a));

        // 4. 转为对外信息（携带 rule_id / bind_id），按分页顺序
        List<BoundHealingRuleInfo> infos = pageIds.stream()
                .map(ruleMap::get)
                .filter(Objects::nonNull)
                .map(rule -> toBoundInfo(rule, bindingMap.get(rule.getId())))
                .collect(Collectors.toList());
        return PageResult.of(pageNum, pageSize, total, infos);
    }

    /** 将规则实体与绑定记录组装为对外信息，补充 rule_id / bind_id。 */
    private BoundHealingRuleInfo toBoundInfo(HealingRule rule, ServiceHealingBinding binding) {
        HealingRuleInfo src = healingRuleService.toInfo(rule);
        BoundHealingRuleInfo info = new BoundHealingRuleInfo();
        info.setRuleId(rule.getId());
        info.setBindId(binding != null ? binding.getId() : null);
        info.setRuleName(src.getRuleName());
        info.setDescription(src.getDescription());
        info.setTriggerConfig(src.getTriggerConfig());
        info.setExecuteCommand(src.getExecuteCommand());
        info.setTimeoutSeconds(src.getTimeoutSeconds());
        info.setMaxRetries(src.getMaxRetries());
        info.setRetryInterval(src.getRetryInterval());
        info.setStatus(src.getStatus());
        info.setCreatedAt(src.getCreatedAt());
        info.setUpdatedAt(src.getUpdatedAt());
        return info;
    }

    @Override
    public void deleteBinding(Long id) {
        ServiceHealingBinding binding = getById(id);
        if (binding == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }
        HealingRule rule = healingRuleMapper.selectById(binding.getRuleId());
        // 物理删除（service_healing_rule_binding 表无 is_deleted 字段）
        removeById(id);
        // 解绑：通知对应客户端移除该绑定（含规则快照）
        notifier.notifyBindingChange(binding, rule, ClientHealingBindingNotifier.ACTION_REMOVE);
    }

    @Override
    public void toggleBindingStatus(Long id, Integer status) {
        ServiceHealingBinding binding = getById(id);
        if (binding == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }
        // 仅更新 status 与 updated_at，避免置空其余字段
        LambdaUpdateWrapper<ServiceHealingBinding> uw = new LambdaUpdateWrapper<>();
        uw.eq(ServiceHealingBinding::getId, id)
                .set(ServiceHealingBinding::getStatus, status)
                .set(ServiceHealingBinding::getUpdatedAt, LocalDateTime.now());
        update(uw);

        ServiceHealingBinding updated = getById(id);
        HealingRule rule = healingRuleMapper.selectById(updated.getRuleId());
        // 启停监听状态：通知对应客户端更新该绑定
        notifier.notifyBindingChange(updated, rule, ClientHealingBindingNotifier.ACTION_UPDATE);
    }
}
