package com.qingruan.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件 HTML 模板工具测试（纯静态方法，无需 Spring 上下文）。
 */
class EmailTemplateUtilTest {

    @Test
    void buildAlertHtmlWithJsonObjectRendersKeyValueTable() {
        String content = "{\"error_msg\":\"Connection refused\",\"metric\":\"cpu\",\"value\":95}";
        String html = EmailTemplateUtil.buildAlertHtml(
                "ALM202608280001", "web-01", "order-service", "CRITICAL", "CPU_HIGH", content);

        assertNotNull(html);
        assertTrue(html.contains("ALM202608280001"), "应包含告警编号");
        assertTrue(html.contains("web-01"), "应包含服务器名");
        assertTrue(html.contains("order-service"), "应包含服务名");
        assertTrue(html.contains("CPU_HIGH"), "应包含告警类型");
        // 结构化内容应被解析为表格单元格，而非原始 JSON 串
        assertTrue(html.contains("error_msg"), "应包含 key");
        assertTrue(html.contains("Connection refused"), "应包含 value");
        assertTrue(!html.contains("\"error_msg\":\"Connection refused\""), "不应原样输出原始 JSON");
    }

    @Test
    void buildAlertHtmlWithJsonArrayRendersList() {
        String content = "[\"disk full\",\"memory leak\"]";
        String html = EmailTemplateUtil.buildAlertHtml(
                "ALM202608280002", "db-01", "mysql", "WARNING", "RESOURCE", content);

        assertNotNull(html);
        assertTrue(html.contains("disk full"));
        assertTrue(html.contains("memory leak"));
    }

    @Test
    void buildAlertHtmlWithPlainTextEscapesAndShows() {
        String content = "line1\nline2 <script>";
        String html = EmailTemplateUtil.buildAlertHtml(
                "ALM202608280003", "srv", "svc", "NORMAL", "INFO", content);

        assertNotNull(html);
        // HTML 特殊字符应被转义，防止注入
        assertTrue(html.contains("&lt;script&gt;"), "尖括号应被转义");
        assertTrue(html.contains("<br/>"), "换行应转为 <br/>");
    }

    @Test
    void buildAlertHtmlWithEmptyContentShowsPlaceholder() {
        String html = EmailTemplateUtil.buildAlertHtml(
                "ALM202608280004", "srv", "svc", "NORMAL", "INFO", "");

        assertNotNull(html);
        assertTrue(html.contains("无附加详情"), "空内容应显示占位符");
    }

    @Test
    void buildNoticeHtmlContainsTitleAndBody() {
        String html = EmailTemplateUtil.buildNoticeHtml("维护通知", "系统将于今晚升级");

        assertNotNull(html);
        assertTrue(html.contains("维护通知"));
        assertTrue(html.contains("系统将于今晚升级"));
    }
}
