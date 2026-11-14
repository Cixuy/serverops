package com.qingruan.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * 邮件 HTML 模板工具类。
 * 提供监控告警等场景的 HTML 邮件模板，避免硬编码拼接字符串。
 */
public class EmailTemplateUtil {

    private static final String COLOR_NORMAL = "#52c41a";
    private static final String COLOR_WARNING = "#faad14";
    private static final String COLOR_CRITICAL = "#ff4d4f";

    /**
     * 构建告警邮件 HTML 正文（与告警事件语义对齐，自动解析结构化 content）。
     *
     * @param alertNo     告警编号
     * @param serverName  服务器名称
     * @param serviceName 服务名
     * @param level       告警级别：NORMAL / WARNING / CRITICAL
     * @param type        告警类型
     * @param content     alertContent 原始 JSON 字符串
     * @return HTML 字符串
     */
    public static String buildAlertHtml(String alertNo, String serverName, String serviceName,
                                        String level, String type, String content) {
        String color = switch (level.toUpperCase()) {
            case "CRITICAL" -> COLOR_CRITICAL;
            case "WARNING" -> COLOR_WARNING;
            default -> COLOR_NORMAL;
        };
        String detailHtml = renderContent(content);
        // 头部与表格字段均来自业务数据或外部上报，统一转义避免 HTML 渲染错位 / 注入
        String escapedLevel = escape(level);
        String escapedAlertNo = escape(alertNo);
        String escapedServerName = escape(serverName);
        String escapedServiceName = escape(serviceName);
        String escapedType = escape(type);

        return """
            <div style="font-family: 'Microsoft YaHei', Arial, sans-serif; max-width: 640px; margin: 0 auto;">
              <div style="background: %s; color: #fff; padding: 16px 24px; border-radius: 6px 6px 0 0;">
                <span style="font-size: 20px; font-weight: bold;">⚠ ServerOps 监控告警</span>
                <span style="float: right; font-size: 14px;">级别: %s</span>
              </div>
              <div style="border: 1px solid #e8e8e8; border-top: none; padding: 24px; border-radius: 0 0 6px 6px;">
                <table style="width: 100%%; border-collapse: collapse; font-size: 14px;">
                  <tr><td style="padding: 8px 0; color: #999; width: 120px;">告警编号</td><td style="font-weight: bold;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #999;">服务器</td><td style="font-weight: bold;">%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #999;">服务</td><td>%s</td></tr>
                  <tr><td style="padding: 8px 0; color: #999;">告警类型</td><td>%s</td></tr>
                </table>
                <div style="margin-top: 16px; padding: 14px 16px; background: #f6f6f6; border-radius: 4px; color: #555; font-size: 13px; line-height: 1.7;">
                  %s
                </div>
                <div style="margin-top: 20px; color: #ccc; font-size: 12px; text-align: center;">
                  本邮件由 ServerOps 服务器智能运维平台自动发送，请勿直接回复。
                </div>
              </div>
            </div>
            """.formatted(color, escapedLevel, escapedAlertNo, escapedServerName, escapedServiceName, escapedType, detailHtml);
    }

    /**
     * 将告警结构化 JSON（错误日志、指标快照等）渲染为易读的 HTML 段落。
     * 兼容普通字符串、JSON 对象（键值对）与 JSON 数组（列表项）。
     */
    private static String renderContent(String content) {
        if (content == null || content.isBlank()) {
            return "<span style='color:#999;'>（无附加详情）</span>";
        }
        Object parsed;
        try {
            parsed = JSON.parse(content);
        } catch (Exception e) {
            // 非 JSON：原样转义输出
            return escape(String.valueOf(content)).replace("\n", "<br/>");
        }

        StringBuilder sb = new StringBuilder();
        if (parsed instanceof JSONObject obj) {
            sb.append("<table style='width:100%; border-collapse:collapse;'>");
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                sb.append("<tr>")
                        .append("<td style='padding:6px 8px; color:#999; vertical-align:top; width:40%;'>")
                        .append(escape(entry.getKey())).append("</td>")
                        .append("<td style='padding:6px 8px; color:#333;'>")
                        .append(escape(String.valueOf(entry.getValue()))).append("</td>")
                        .append("</tr>");
            }
            sb.append("</table>");
        } else if (parsed instanceof JSONArray arr) {
            sb.append("<ul style='margin:0; padding-left:20px;'>");
            for (Object item : arr) {
                sb.append("<li style='margin:4px 0;'>")
                        .append(escape(String.valueOf(item))).append("</li>");
            }
            sb.append("</ul>");
        } else {
            sb.append(escape(String.valueOf(parsed)).replace("\n", "<br/>"));
        }
        return sb.toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 构建系统通知邮件 HTML 正文。
     */
    public static String buildNoticeHtml(String title, String body) {
        return """
            <div style="font-family: 'Microsoft YaHei', Arial, sans-serif; max-width: 640px; margin: 0 auto;">
              <div style="background: #1890ff; color: #fff; padding: 16px 24px; border-radius: 6px 6px 0 0; font-size: 18px; font-weight: bold;">
                %s
              </div>
              <div style="border: 1px solid #e8e8e8; border-top: none; padding: 24px; border-radius: 0 0 6px 6px; font-size: 14px; color: #333; line-height: 1.8;">
                %s
              </div>
              <div style="color: #ccc; font-size: 12px; text-align: center; margin-top: 12px;">
                —— ServerOps 服务器智能运维平台
              </div>
            </div>
            """.formatted(title, body);
    }

    private EmailTemplateUtil() {
    }
}
