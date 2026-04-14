package com.mcagents.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 兼容 API 的流式与非流式调用。
 */
final class AgentHttpClient {
    static ApiResult callOpenAICompatibleApi(ServerPlayer player, JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            return callOpenAICompatibleApiStreaming(requestMessages, currentConfig);
        } catch (AgentUserException streamError) {
            if (!streamError.isHttpStatusCode(400)) {
                throw streamError;
            }
            return callOpenAICompatibleApiFallback(requestMessages, currentConfig);
        }
    }

    static ApiResult callOpenAICompatibleApiFallback(JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            JsonObject requestBody = buildRequestBody(requestMessages, currentConfig.model(), false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig.apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + currentConfig.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = AgentState.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw AgentUserException.httpStatus(response.statusCode());
            }

            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = result.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.invalid_choices");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.invalid_message");
            }

            String content = extractText(message.get("content")).trim();
            if (content.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.empty");
            }
            Integer totalTokens = readUsageTotalTokens(result);
            return new ApiResult(content, totalTokens, "");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentUserException("command.modid.agent.chat.request.failed.network", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static ApiResult callOpenAICompatibleApiStreaming(JsonArray requestMessages, AgentConfig currentConfig) {
        try {
            JsonObject requestBody = buildRequestBody(requestMessages, currentConfig.model(), true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(currentConfig.apiUrl()))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + currentConfig.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response = AgentState.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw AgentUserException.httpStatus(response.statusCode());
            }

            AgentStreamState streamState = new AgentStreamState();
            StringBuilder eventData = new StringBuilder();

            try (java.util.stream.Stream<String> lines = response.body()) {
                java.util.Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (line == null) {
                        continue;
                    }

                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        if (consumeEventData(eventData, streamState)) {
                            break;
                        }
                        continue;
                    }

                    if (trimmed.startsWith("data:")) {
                        if (eventData.length() > 0) {
                            eventData.append('\n');
                        }
                        eventData.append(trimmed.substring(5).trim());
                    }
                }
            }

            consumeEventData(eventData, streamState);
            streamState.flushPending(true);
            String finalAnswer = streamState.answerText.toString().trim();
            if (finalAnswer.isEmpty()) {
                throw new AgentUserException("command.modid.agent.chat.request.failed.empty");
            }
            return new ApiResult(finalAnswer, streamState.totalTokens, streamState.thinkingText.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentUserException("command.modid.agent.chat.request.failed.network", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static JsonObject buildRequestBody(JsonArray messages, String model, boolean stream) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("stream", stream);
        requestBody.add("messages", messages);
        return requestBody;
    }

    private static boolean consumeEventData(StringBuilder eventData, AgentStreamState streamState) {
        if (eventData.length() == 0) {
            return false;
        }
        String payload = eventData.toString().trim();
        eventData.setLength(0);

        if (payload.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }

        try {
            JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
            Integer totalTokens = readUsageTotalTokens(chunk);
            if (totalTokens != null) {
                streamState.totalTokens = totalTokens;
            }
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return false;
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject delta = firstChoice.has("delta") ? firstChoice.getAsJsonObject("delta") : null;
            JsonObject message = firstChoice.has("message") ? firstChoice.getAsJsonObject("message") : null;

            if (delta != null) {
                appendChunk(streamState, delta);
            } else if (message != null) {
                appendChunk(streamState, message);
            }
            return false;
        } catch (Exception e) {
            // 忽略无法解析的片段，继续处理后续流。
            return false;
        }
    }

    private static void appendChunk(AgentStreamState streamState, JsonObject source) {
        String reasoningText = extractText(source.get("reasoning_content"))
                + extractText(source.get("reasoning"))
                + extractText(source.get("thinking"));
        String answerText = extractText(source.get("content"));

        String safeReasoning = AgentSanitize.sanitizeThinkingForDisplay(reasoningText);
        if (!safeReasoning.isEmpty()) {
            streamState.thinkingPending.append(safeReasoning);
            streamState.thinkingText.append(safeReasoning);
        }
        if (!answerText.isEmpty()) {
            streamState.answerPending.append(answerText);
            streamState.answerText.append(answerText);
        }

        streamState.flushPending(false);
    }

    static String extractText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : element.getAsJsonArray()) {
                sb.append(extractText(item));
            }
            return sb.toString();
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("text")) {
                return extractText(obj.get("text"));
            }
            if (obj.has("content")) {
                return extractText(obj.get("content"));
            }
            if (obj.has("reasoning_content")) {
                return extractText(obj.get("reasoning_content"));
            }
            if (obj.has("reasoning")) {
                return extractText(obj.get("reasoning"));
            }
        }
        return "";
    }

    static Integer readUsageTotalTokens(JsonObject object) {
        if (object == null || !object.has("usage") || !object.get("usage").isJsonObject()) {
            return null;
        }
        JsonObject usage = object.getAsJsonObject("usage");
        if (!usage.has("total_tokens")) {
            return null;
        }
        try {
            return usage.get("total_tokens").getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private AgentHttpClient() {
    }
}

/**
 * 流式 SSE 解析时的缓冲与「思考」展示节奏。
 */
final class AgentStreamState {
    private static final int FLUSH_SIZE = 36;
    private static final long FLUSH_INTERVAL_MS = 800;

    final StringBuilder thinkingText = new StringBuilder();
    final StringBuilder answerText = new StringBuilder();
    final StringBuilder thinkingPending = new StringBuilder();
    final StringBuilder answerPending = new StringBuilder();
    Integer totalTokens = null;
    private long lastFlushAt = System.currentTimeMillis();

    AgentStreamState() {
    }

    void flushPending(boolean force) {
        long now = System.currentTimeMillis();
        boolean shouldFlush = force
                || thinkingPending.length() >= FLUSH_SIZE
                || answerPending.length() >= FLUSH_SIZE
                || (now - lastFlushAt) >= FLUSH_INTERVAL_MS;
        if (!shouldFlush) {
            return;
        }

        if (thinkingPending.length() > 0) {
            // 不在流式过程中向玩家展示；完整思考文本在请求结束后由 ApiResult.streamingThinking 返回，由 handleAiReply 统一决定是否展示。
            thinkingPending.setLength(0);
        }
        answerPending.setLength(0);
        lastFlushAt = now;
    }
}
