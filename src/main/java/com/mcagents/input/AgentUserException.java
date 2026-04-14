package com.mcagents.input;

import net.minecraft.network.chat.MutableComponent;

/**
 * AI 请求或解析失败时抛出的业务异常，带可翻译键。
 */
final class AgentUserException extends RuntimeException {
    private final String translationKey;
    private final Object[] args;
    private final Integer httpStatusCode;

    AgentUserException(String translationKey, Integer httpStatusCode, Object... args) {
        super(translationKey);
        this.translationKey = translationKey;
        this.args = args;
        this.httpStatusCode = httpStatusCode;
    }

    AgentUserException(String translationKey, Object... args) {
        this(translationKey, null, args);
    }

    static AgentUserException httpStatus(int statusCode) {
        return new AgentUserException("command.modid.agent.chat.request.failed.http", statusCode, statusCode);
    }

    boolean isHttpStatusCode(int statusCode) {
        return httpStatusCode != null && httpStatusCode == statusCode;
    }

    MutableComponent toComponent() {
        String fallback = switch (translationKey) {
            case "command.modid.agent.chat.request.failed.http" -> "AI 请求失败：HTTP 状态码 %s";
            case "command.modid.agent.chat.request.failed.invalid_choices" -> "AI 响应格式错误：缺少 choices 字段";
            case "command.modid.agent.chat.request.failed.invalid_message" -> "AI 响应格式错误：缺少 message 字段";
            case "command.modid.agent.chat.request.failed.empty" -> "AI 响应为空";
            case "command.modid.agent.chat.request.failed.network" -> "AI 网络请求失败：%s";
            default -> "AI 请求失败";
        };
        return AgentMessaging.i18n(translationKey, fallback, args);
    }
}
