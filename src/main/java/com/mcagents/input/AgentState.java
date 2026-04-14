package com.mcagents.input;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 模块共享的可变状态与 HTTP 客户端。
 */
final class AgentState {
    static final int HTTP_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    static final ExecutorService HTTP_EXECUTOR = new ThreadPoolExecutor(
            HTTP_THREADS,
            HTTP_THREADS,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "mcagents-agent-http");
                t.setDaemon(true);
                return t;
            }
    );

    static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static volatile AgentConfig config = new AgentConfig(
            AgentConstants.DEFAULT_API_URL,
            "",
            AgentConstants.DEFAULT_MODEL,
            AgentConstants.DEFAULT_MAX_CONTEXT_TOKENS
    );
    static volatile String controlBotSystemPrompt = "";
    static final Map<UUID, ConversationState> CONVERSATIONS = new ConcurrentHashMap<>();
    /**
     * 同一玩家上多条 ask/search 串行执行，避免并行的 prepare/commit 打乱对话状态（例如先创建假人再搜 Wiki）。
     */
    static final ConcurrentHashMap<UUID, CompletableFuture<Void>> PLAYER_AI_TASK_CHAIN = new ConcurrentHashMap<>();

    private AgentState() {
    }
}
