package com.mcagents.input;

/**
 * 与上下文窗口估算相关的工具方法。
 */
final class AgentTokenUtils {
    static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        double tokenUnits = 0.0D;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch <= 0x7F) {
                tokenUnits += 0.25D;
            } else {
                tokenUnits += 1.0D;
            }
        }
        return Math.max(1, (int) Math.ceil(tokenUnits));
    }

    private AgentTokenUtils() {
    }
}
