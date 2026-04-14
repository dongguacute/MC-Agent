package com.mcagents.input;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 回复展示前的清洗与控制指令/ Wiki 指令行的剥离。
 */
final class AgentSanitize {
    static String getControlBotSystemPrompt() {
        return AgentState.controlBotSystemPrompt == null ? "" : AgentState.controlBotSystemPrompt;
    }

    /**
     * 移除整行 {@code [CONTROL_BOT]} 指令（与 {@link #sanitizeReplyForDisplay} 一致，供提交/草稿用）。
     */
    static String stripControlDirectives(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String rawLine : reply.replace("\r\n", "\n").split("\n", -1)) {
            String line = rawLine.trim();
            if (AgentConstants.CONTROL_DIRECTIVE_LINE_PATTERN.matcher(line).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rawLine);
        }
        return sb.toString().trim();
    }

    /** 去掉 Wiki 与控制行后的「自然语言」草稿（工具优先轮可能仅有指令行）。 */
    static String naturalLanguageAfterToolLines(String reply) {
        return stripWikiSearchDirectives(stripControlDirectives(reply)).trim();
    }

    static String stripWikiSearchDirectives(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String rawLine : reply.replace("\r\n", "\n").split("\n", -1)) {
            String line = rawLine.trim();
            if (AgentConstants.WIKI_SEARCH_DIRECTIVE_LINE_PATTERN.matcher(line).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rawLine);
        }
        return sb.toString().trim();
    }

    static String sanitizeReplyForDisplay(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        String sanitized = reply.replace("\r\n", "\n").trim();
        sanitized = AgentConstants.CONTROL_DIRECTIVE_LINE_PATTERN.matcher(sanitized).replaceAll("").trim();
        sanitized = stripWikiSearchDirectives(sanitized).trim();
        String systemPrompt = getControlBotSystemPrompt();
        if (!systemPrompt.isBlank()) {
            sanitized = sanitized.replace(systemPrompt.trim(), "").trim();
        }
        return sanitized;
    }

    static String sanitizeThinkingForDisplay(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return "";
        }
        String sanitized = reasoning.replace("\r\n", "\n").trim();
        if (sanitized.isEmpty()) {
            return "";
        }
        sanitized = AgentConstants.CONTROL_DIRECTIVE_LINE_PATTERN.matcher(sanitized).replaceAll("").trim();
        String systemPrompt = getControlBotSystemPrompt();
        if (!systemPrompt.isBlank()) {
            sanitized = sanitized.replace(systemPrompt.trim(), "").trim();
        }
        if (AgentConstants.PROMPT_LEAK_PATTERN.matcher(sanitized).find()) {
            return "";
        }
        return sanitized;
    }

    static boolean hasControlDirective(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return AgentConstants.CONTROL_DIRECTIVE_PRESENT_PATTERN.matcher(text).find();
    }

    /**
     * 从模型原文中提取 {@code [MC_WIKI_SEARCH]} 查询串（顺序保留，不截断条数；调用方限制数量）。
     */
    static List<String> extractWikiSearchQueries(String reply) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return out;
        }
        for (String rawLine : reply.replace("\r\n", "\n").split("\n")) {
            Matcher m = AgentConstants.WIKI_SEARCH_DIRECTIVE_LINE_PATTERN.matcher(rawLine.trim());
            if (m.matches()) {
                String q = m.group(1).trim();
                if (!q.isEmpty()) {
                    out.add(q);
                }
            }
        }
        return out;
    }

    private AgentSanitize() {
    }
}
