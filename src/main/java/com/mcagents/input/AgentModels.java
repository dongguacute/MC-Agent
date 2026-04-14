package com.mcagents.input;

import com.google.gson.JsonArray;
import net.minecraft.network.chat.MutableComponent;

/**
 * Agent 请求/会话相关的不可变数据载体。
 */
record AgentConfig(String apiUrl, String apiKey, String model, int maxContextTokens) {
}

record ConversationPrepareResult(
        boolean allowed,
        JsonArray requestMessages,
        long version,
        int systemTokens,
        MutableComponent blockMessage
) {
}

record ContextStatus(int remainingTokens, boolean full) {
}

record ChatMessage(String role, String content) {
    int tokenSize() {
        return AgentTokenUtils.estimateTokens(content) + AgentConstants.MESSAGE_OVERHEAD_TOKENS;
    }
}

record ApiResult(String reply, Integer totalTokens, String streamingThinking) {
    ApiResult(String reply, Integer totalTokens) {
        this(reply, totalTokens, "");
    }
}
