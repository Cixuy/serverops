package com.qingruan.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.common.result.PageResult;
import com.qingruan.dto.request.AlertEventReportRequest;
import com.qingruan.entity.AlertActionLog;
import com.qingruan.entity.AlertEvent;
import com.qingruan.mapper.AlertActionLogMapper;
import com.qingruan.mapper.AlertEventMapper;
import com.qingruan.common.constant.AlertActionResult;
import com.qingruan.entity.HealingRule;
import com.qingruan.entity.ServerInfo;
import com.qingruan.entity.SysUser;
import com.qingruan.mapper.HealingRuleMapper;
import com.qingruan.mapper.ServerInfoMapper;
import com.qingruan.mapper.SysUserMapper;
import com.qingruan.service.AlertEventService;
import com.qingruan.service.MailService;
import com.qingruan.util.EmailTemplateUtil;
import com.qingruan.vo.AlertActionLogInfo;
import com.qingruan.vo.AlertActionRequest;
import com.qingruan.vo.AlertDetailResponse;
import com.qingruan.vo.AlertEventInfo;
import com.qingruan.vo.AlertEventReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.qingruan.entity.ServerInfo;
import com.qingruan.entity.SysUser;
import com.qingruan.mapper.ServerInfoMapper;
import com.qingruan.mapper.SysUserMapper;
import com.qingruan.service.MailService;
import com.qingruan.util.EmailTemplateUtil;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEventServiceImpl extends ServiceImpl<AlertEventMapper, AlertEvent> implements AlertEventService {

    /** 告警编号日期前缀格式，如 ALM20260824 */
    private static final DateTimeFormatter ALERT_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 告警编号当日序号自增器（Redis 不可用时降级使用） */
    private static final AtomicLong alertNoSeq = new AtomicLong(0);
    /** 告警编号 Redis 自增 key 前缀，key 形如 serverops:alert_seq:20260824 */
    private static final String ALERT_NO_SEQ_KEY_PREFIX = "serverops:alert_seq:";
    /** 告警编号 Redis key 保留时长 */
    private static final Duration ALERT_NO_SEQ_TTL = Duration.ofDays(2);

    private final AlertActionLogMapper alertActionLogMapper;
    private final HealingRuleMapper healingRuleMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ServerInfoMapper serverInfoMapper;
    private final SysUserMapper sysUserMapper;
    private final MailService mailService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertEventReportResponse reportAlertEvent(AlertEventReportRequest request) {
        AlertEvent event = new AlertEvent();
        event.setServerCode(request.getServerCode());
        event.setConfigId(request.getConfigId());
        event.setServiceName(request.getServiceName());
        event.setAlertType(request.getAlertType());
        // alert_level 未传时取系统默认 CRITICAL
        event.setAlertLevel(StringUtils.hasText(request.getAlertLevel()) ? request.getAlertLevel() : "CRITICAL");
        // 关联交易的自愈规则ID
        event.setRuleId(request.getRuleId());
        // alert_content 为 MySQL JSON 列，实体中以字符串存储，上报对象序列化为字符串
        event.setAlertContent(JSON.toJSONString(request.getAlertContent()));
        // 触发时间：传入带时区偏移的时间转为系统本地时区的 LocalDateTime
        LocalDateTime triggerTime = request.getTriggerTime()
                .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        event.setTriggerTime(triggerTime);
        // 服务端最终认定状态：关联且启用的自愈规则视为已自愈(RESOLVED)，否则待处理(PENDING)
        String initialStatus = determineInitialStatus(request.getRuleId());
        event.setAlertStatus(initialStatus);
        // 与状态联动填充自愈结果：已自愈置 SUCCESS 并写入恢复时间；未命中有效规则置 SKIPPED
        if ("RESOLVED".equals(initialStatus)) {
            event.setAutoHealStatus("SUCCESS");
            event.setResolveTime(LocalDateTime.now());
        } else {
            event.setAutoHealStatus("SKIPPED");
        }
        event.setAlertNo(generateAlertNo(triggerTime));
        event.setCreatedAt(LocalDateTime.now());

        // insert 后自增主键回填到 event.getId()
        save(event);

        // 触发告警邮件通知（异步发送，失败自动重试并落库审计，不阻塞主流程）
        try {
            sendAlertEmail(event);
        } catch (Exception e) {
            // 邮件通知失败不影响告警上报主流程
            log.error("触发告警邮件通知异常, alertId={}", event.getId(), e);
        }

        AlertEventReportResponse response = new AlertEventReportResponse();
        response.setId(event.getId());
        response.setAlertNo(event.getAlertNo());
        response.setAlertStatus(event.getAlertStatus());
        return response;
    }

    /**
     * 触发告警邮件通知：收集系统中所有有效邮箱用户作为接收人，
     * 查询告警服务器主机名，组装 HTML 正文后异步发送。
     */
    private void sendAlertEmail(AlertEvent event) {
        // 1. 收集接收人邮箱（所有配置了有效邮箱的用户）
        List<String> receivers = sysUserMapper.selectList(null).stream()
                .map(SysUser::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (receivers.isEmpty()) {
            log.warn("未配置任何接收邮箱，跳过告警邮件发送, alertId={}", event.getId());
            return;
        }

        // 2. 查询告警服务器主机名（用于邮件正文展示）
        String serverName = event.getServerCode();
        try {
            ServerInfo serverInfo = serverInfoMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ServerInfo>()
                            .eq("server_code", event.getServerCode()));
            if (serverInfo != null && serverInfo.getHostname() != null) {
                serverName = serverInfo.getHostname();
            }
        } catch (Exception e) {
            log.warn("查询告警服务器主机名失败, serverCode={}", event.getServerCode(), e);
        }

        // 3. 组装告警正文 HTML（自动解析结构化 content，渲染为可读表格/列表）
        String html = EmailTemplateUtil.buildAlertHtml(
                event.getAlertNo(),
                serverName,
                event.getServiceName(),
                event.getAlertLevel(),
                event.getAlertType(),
                event.getAlertContent());

        // 4. 异步发送（内部含重试与审计落库）
        String receiverEmails = String.join(",", receivers);
        String subject = String.format("[%s] ServerOps 告警通知 - %s",
                event.getAlertLevel(), event.getServiceName());
        mailService.sendAlertNotification(
                event.getId(),
                receiverEmails,
                subject,
                html,
                true);
    }

    /**
     * 生成告警编号：ALM + yyyyMMdd + 当日4位流水号，如 ALM202608240001
     * <p>
     * 优先使用 Redis INCR 原子自增（多实例/并发下保证序号唯一，杜绝重复）；
     * 当 Redis 中不存在对应日期的 key（例如不同开发机的 Redis 实例各不相同、key 过期等）时，
     * 先以数据库当天已有告警的最大序号为基准初始化 key，再从该基准继续自增，避免不同 Redis 之间序号冲突；
     * Redis 不可用时降级为"数据库当天告警数 + 内存自增"，仍可保证单实例下不冲突。
     */
    private String generateAlertNo(LocalDateTime triggerTime) {
        String date = triggerTime.format(ALERT_NO_DATE_FORMAT);
        long seq;
        try {
            String key = ALERT_NO_SEQ_KEY_PREFIX + date;
            // key 不存在（首次使用或换了 Redis 实例）时，先用数据库当天已有告警的最大序号初始化，避免序号冲突
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                Long incr = stringRedisTemplate.opsForValue().increment(key);
                seq = incr != null ? incr : fallbackSeq(date);
            } else {
                long dbSeq = maxSeq(date);
                // 以数据库基准初始化后再自增，确保首条即从 dbSeq+1 开始
                Long incr = stringRedisTemplate.opsForValue().increment(key, dbSeq);
                seq = incr != null ? incr : fallbackSeq(date);
                // 新建的 key 设置 TTL，避免 key 长期堆积
                stringRedisTemplate.expire(key, ALERT_NO_SEQ_TTL);
            }
        } catch (Exception e) {
            log.warn("Redis 告警编号自增不可用，降级为数据库统计: {}", e.getMessage());
            seq = fallbackSeq(date);
        }
        return "ALM" + date + String.format("%04d", seq);
    }

    /**
     * 查询当天告警编号中的最大序号，作为 Redis 初始化/兜底时的基准。
     * 无数据时返回 0，后续从 1 开始自增。
     */
    private long maxSeq(String date) {
        AlertEvent max = getOne(new LambdaQueryWrapper<AlertEvent>()
                .likeRight(AlertEvent::getAlertNo, "ALM" + date)
                .orderByDesc(AlertEvent::getAlertNo)
                .last("LIMIT 1"), false);
        if (max == null || !StringUtils.hasText(max.getAlertNo())) {
            return 0;
        }
        try {
            // 编号形如 ALM202608240001，取其末尾序号部分
            String no = max.getAlertNo();
            return Long.parseLong(no.substring("ALM".length() + date.length()));
        } catch (Exception e) {
            log.warn("解析告警编号最大序号失败，按 0 处理: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 降级方案：查询当天已有告警数作为序号基准，配合进程内原子自增。
     * 仅用于 Redis 不可用时的兜底，多实例部署下仍可能存在极小概率冲突（依赖 alert_no 唯一约束兜底）。
     */
    private long fallbackSeq(String date) {
        long count = count(new LambdaQueryWrapper<AlertEvent>()
                .likeRight(AlertEvent::getAlertNo, "ALM" + date));
        return Math.max(count + 1, alertNoSeq.incrementAndGet());
    }

    /**
     * 服务端最终认定告警状态：
     * 关联的自愈规则存在且处于启用状态(1) → 视为已自愈，标记为 RESOLVED；
     * 规则不存在 / 已停用 / 查询异常 → 标记为 PENDING 等待人工或后续处置。
     */
    private String determineInitialStatus(Long ruleId) {
        if (ruleId == null) {
            return "PENDING";
        }
        try {
            HealingRule rule = healingRuleMapper.selectById(ruleId);
            if (rule != null && rule.getStatus() != null && rule.getStatus() == 1) {
                return "RESOLVED";
            }
        } catch (Exception e) {
            log.warn("查询关联自愈规则失败，告警状态置为 PENDING: {}", e.getMessage());
        }
        return "PENDING";
    }

    @Override
    public PageResult<AlertEventInfo> getAlertEventList(Integer pageNum, Integer pageSize,
                                                        String serverCode, String alertLevel, String alertStatus,
                                                        LocalDateTime startTime, LocalDateTime endTime) {
        Page<AlertEvent> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<AlertEvent> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(serverCode)) {
            wrapper.eq(AlertEvent::getServerCode, serverCode);
        }
        if (StringUtils.hasText(alertLevel)) {
            wrapper.eq(AlertEvent::getAlertLevel, alertLevel);
        }
        if (StringUtils.hasText(alertStatus)) {
            wrapper.eq(AlertEvent::getAlertStatus, alertStatus);
        }
        if (startTime != null && endTime != null) {
            wrapper.between(AlertEvent::getTriggerTime, startTime, endTime);
        } else if (startTime != null) {
            wrapper.ge(AlertEvent::getTriggerTime, startTime);
        } else if (endTime != null) {
            wrapper.le(AlertEvent::getTriggerTime, endTime);
        }
        // 按创建时间倒序，保证"最近上报/录入"的告警优先展示在首页；trigger_time 是告警业务触发时间，可能远早于录入时间
        wrapper.orderByDesc(AlertEvent::getCreatedAt);

        IPage<AlertEvent> result = page(page, wrapper);
        List<AlertEventInfo> infos = result.getRecords().stream().map(this::toInfo).collect(Collectors.toList());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), infos);
    }

    private AlertEventInfo toInfo(AlertEvent e) {
        AlertEventInfo info = new AlertEventInfo();
        info.setId(e.getId());
        info.setAlertNo(e.getAlertNo());
        info.setServerCode(e.getServerCode());
        info.setConfigId(e.getConfigId());
        info.setRuleId(e.getRuleId());
        info.setServiceName(e.getServiceName());
        info.setAlertType(e.getAlertType());
        info.setAlertLevel(e.getAlertLevel());
        // alert_content 为 MySQL JSON 列，实体中以字符串存储，此处解析为对象输出
        info.setAlertContent(e.getAlertContent() != null ? JSON.parse(e.getAlertContent()) : null);
        info.setAlertStatus(e.getAlertStatus());
        info.setTriggerTime(e.getTriggerTime());
        info.setResolveTime(e.getResolveTime());
        info.setCreatedAt(e.getCreatedAt());
        return info;
    }

    @Override
    public AlertDetailResponse getAlertDetail(Long id) {
        AlertEvent alertEvent = getById(id);
        if (alertEvent == null) {
            return null;
        }
        AlertDetailResponse response = new AlertDetailResponse();
        response.setAlertInfo(toInfo(alertEvent));

        LambdaQueryWrapper<AlertActionLog> logWrapper = new LambdaQueryWrapper<>();
        logWrapper.eq(AlertActionLog::getAlertId, id).orderByAsc(AlertActionLog::getCreatedAt);
        List<AlertActionLogInfo> logs = alertActionLogMapper.selectList(logWrapper).stream()
                .map(this::toLogInfo).collect(Collectors.toList());
        response.setActionLogs(logs);
        return response;
    }

    private AlertActionLogInfo toLogInfo(AlertActionLog log) {
        AlertActionLogInfo info = new AlertActionLogInfo();
        info.setId(log.getId());
        info.setAlertId(log.getAlertId());
        info.setActionType(log.getActionType());
        info.setOperatorId(log.getOperatorId());
        info.setOperatorName(log.getOperatorName());
        info.setActionResult(log.getActionResult());
        info.setActionDetail(log.getActionDetail());
        info.setCreatedAt(log.getCreatedAt());
        return info;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertActionResult performAction(Long id, AlertActionRequest request) {
        AlertEvent alertEvent = getById(id);
        if (alertEvent == null) {
            return AlertActionResult.NOT_FOUND;
        }
        // 已恢复(RESOLVED)或已搁置(IGNORED)的告警不允许重复处理
        String currentStatus = alertEvent.getAlertStatus();
        if ("RESOLVED".equals(currentStatus) || "IGNORED".equals(currentStatus)) {
            return AlertActionResult.ALREADY_CLOSED;
        }
        String actionType = request.getActionType();
        // 根据操作类型映射告警状态：自愈→HEALING，手动恢复→RESOLVED(并写恢复时间)，搁置→IGNORED
        String newStatus;
        boolean resolve = false;
        switch (actionType) {
            case "AUTO_HEAL":
                newStatus = "HEALING";
                break;
            case "MANUAL_RECOVER":
                newStatus = "RESOLVED";
                resolve = true;
                break;
            case "IGNORE":
                newStatus = "IGNORED";
                break;
            default:
                // 由控制器已校验，理论上不会进入
                return AlertActionResult.NOT_FOUND;
        }

        // 仅更新需要变更的列，避免 updateById 把其余字段置空
        LambdaUpdateWrapper<AlertEvent> uw = new LambdaUpdateWrapper<>();
        uw.eq(AlertEvent::getId, id).set(AlertEvent::getAlertStatus, newStatus);
        if (resolve) {
            uw.set(AlertEvent::getResolveTime, LocalDateTime.now());
        }
        update(uw);

        // 记录处置操作流水（当前无登录态，操作人信息留空）
        AlertActionLog log = new AlertActionLog();
        log.setAlertId(id);
        log.setActionType(actionType);
        log.setActionResult("SUCCESS");
        log.setActionDetail(request.getActionDetail());
        log.setCreatedAt(LocalDateTime.now());
        alertActionLogMapper.insert(log);
        return AlertActionResult.SUCCESS;
    }
}
