package com.mcagents.Agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagents.input.Agent;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Startbot {
    private static final String RECORD_FILE_NAME = "agent_records.json";
    private static final String BOT_STATE_FILE_NAME = "agent_bot_state.json";
    private static final Pattern BOT_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern AI_CONTROL_PATTERN = Pattern.compile("(?i)\\[CONTROL_BOT\\]\\s*(join|leave)\\s+(?:\"([^\"]{1,64})\"|([^\\s]{1,64}))");

    public static int handleBotJoinCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        return handleControl(context.getSource(), "join", botName);
    }

    public static int handleBotLeaveCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        return handleControl(context.getSource(), "leave", botName);
    }

    public static boolean tryHandleAiDirective(ServerPlayer player, String aiReply) {
        if (aiReply == null || aiReply.isBlank()) {
            return false;
        }

        Matcher matcher = AI_CONTROL_PATTERN.matcher(aiReply);
        if (!matcher.find()) {
            return false;
        }

        String action = matcher.group(1).toLowerCase();
        String botNameOrTag = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        CommandSourceStack source = createAgentSourceFromPlayer(player);
        int result = handleControl(source, action, botNameOrTag);
        return result == 1;
    }

    private static int handleControl(CommandSourceStack source, String action, String botNameOrTag) {
        List<String> targetBotNames = resolveBotTargets(source, botNameOrTag);
        if (targetBotNames == null || targetBotNames.isEmpty()) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        String subCommand;
        if ("join".equalsIgnoreCase(action)) {
            subCommand = "spawn";
        } else if ("leave".equalsIgnoreCase(action)) {
            subCommand = "kill";
        } else {
            source.sendFailure(Component.literal("未知操作，仅支持 join / leave"));
            return 0;
        }

        int successCount = 0;
        List<String> failedBots = new ArrayList<>();
        for (String botName : targetBotNames) {
            if ("leave".equalsIgnoreCase(action)) {
                saveBotCurrentPosition(server, botName);
            }
            String carpetCommand = "player " + botName + " " + subCommand;
            try {
                int executeResult = server.getCommands().getDispatcher().execute(
                        carpetCommand,
                        source.withPermission(4)
                );
                if (executeResult > 0) {
                    if ("join".equalsIgnoreCase(action)) {
                        restoreBotPositionIfPresent(source, server, botName);
                    }
                    successCount++;
                } else {
                    failedBots.add(botName);
                }
            } catch (CommandSyntaxException e) {
                failedBots.add(botName);
                source.sendFailure(Component.literal("Carpet 命令语法/上下文错误: /" + carpetCommand + "，" + e.getMessage()));
            } catch (Exception e) {
                failedBots.add(botName);
                source.sendFailure(Component.literal("执行 Carpet 命令失败: /" + carpetCommand + "，" + e.getMessage()));
            }
        }

        int finalSuccessCount = successCount;
        source.sendSuccess(() -> Component.literal("批量执行完成：成功 " + finalSuccessCount + " 个，失败 " + failedBots.size() + " 个"), true);
        if (!failedBots.isEmpty()) {
            source.sendFailure(Component.literal("失败 bot: " + String.join(", ", failedBots)));
        }
        return successCount > 0 ? 1 : 0;
    }

    private static boolean isValidBotName(String botName) {
        return botName != null && BOT_NAME_PATTERN.matcher(botName).matches();
    }

    private static List<String> resolveBotTargets(CommandSourceStack source, String input) {
        if (input == null || input.isBlank()) {
            source.sendFailure(Component.literal("请输入 bot 名称或已记录的 tag"));
            return null;
        }

        List<String> matchedBots = new ArrayList<>();
        Path recordFile;
        try {
            recordFile = getRecordFile(source.getServer());
            JsonArray records = readRecords(recordFile);
            source.sendSuccess(() -> Component.literal("已读取存储库: " + recordFile.toAbsolutePath() + "，记录数 " + records.size()), false);
            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject record = element.getAsJsonObject();
                String tag = record.has("tag") ? record.get("tag").getAsString() : "";
                String botName = record.has("bot_name") ? record.get("bot_name").getAsString() : "";
                if (!input.equals(tag)) {
                    continue;
                }
                if (!isValidBotName(botName)) {
                    continue;
                }
                if (!matchedBots.contains(botName)) {
                    matchedBots.add(botName);
                }
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("读取存储库失败: " + e.getMessage()));
            return null;
        }

        if (!matchedBots.isEmpty()) {
            source.sendSuccess(() -> Component.literal("tag 命中 bot: " + String.join(", ", matchedBots)), false);
            return matchedBots;
        }

        if (isValidBotName(input)) {
            List<String> single = new ArrayList<>();
            single.add(input);
            return single;
        }

        source.sendFailure(Component.literal("未找到该 tag 对应的 bot，且输入也不是合法 bot_name（3-16 位字母/数字/下划线）"));
        return null;
    }

    private static Path getRecordFile(MinecraftServer server) throws IOException {
        Path dataDir = Agent.getAgentDataDirectory(server);
        Files.createDirectories(dataDir);
        return dataDir.resolve(RECORD_FILE_NAME);
    }

    private static Path getBotStateFile(MinecraftServer server) throws IOException {
        Path dataDir = Agent.getAgentDataDirectory(server);
        Files.createDirectories(dataDir);
        return dataDir.resolve(BOT_STATE_FILE_NAME);
    }

    private static JsonArray readRecords(Path dataFile) throws IOException {
        if (!Files.exists(dataFile)) {
            return new JsonArray();
        }
        String content = Files.readString(dataFile).trim();
        if (content.isEmpty()) {
            return new JsonArray();
        }
        try {
            return JsonParser.parseString(content).getAsJsonArray();
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }

    private static void saveBotCurrentPosition(MinecraftServer server, String botName) {
        ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
        if (bot == null) {
            return;
        }
        try {
            JsonObject states = readBotStates(getBotStateFile(server));
            JsonObject botState = new JsonObject();
            botState.addProperty("dimension", bot.level().dimension().location().toString());
            botState.addProperty("x", bot.getX());
            botState.addProperty("y", bot.getY());
            botState.addProperty("z", bot.getZ());
            botState.addProperty("yaw", bot.getYRot());
            botState.addProperty("pitch", bot.getXRot());
            states.add(botName, botState);
            writeBotStates(getBotStateFile(server), states);
        } catch (IOException ignored) {
            // 记录位置失败不阻断主流程。
        }
    }

    private static void restoreBotPositionIfPresent(CommandSourceStack source, MinecraftServer server, String botName) {
        try {
            JsonObject states = readBotStates(getBotStateFile(server));
            if (!states.has(botName) || !states.get(botName).isJsonObject()) {
                return;
            }
            JsonObject botState = states.getAsJsonObject(botName);
            String dimension = botState.has("dimension") ? botState.get("dimension").getAsString() : "minecraft:overworld";
            double x = botState.has("x") ? botState.get("x").getAsDouble() : 0.0D;
            double y = botState.has("y") ? botState.get("y").getAsDouble() : 64.0D;
            double z = botState.has("z") ? botState.get("z").getAsDouble() : 0.0D;
            float yaw = botState.has("yaw") ? botState.get("yaw").getAsFloat() : 0.0F;
            float pitch = botState.has("pitch") ? botState.get("pitch").getAsFloat() : 0.0F;

            String tpCommand = "execute in " + dimension + " run tp " + botName + " " + x + " " + y + " " + z + " " + yaw + " " + pitch;
            server.getCommands().getDispatcher().execute(tpCommand, source.withPermission(4));
        } catch (Exception e) {
            source.sendFailure(Component.literal("已上线但恢复原位置失败: " + botName + "，" + e.getMessage()));
        }
    }

    private static JsonObject readBotStates(Path stateFile) throws IOException {
        if (!Files.exists(stateFile)) {
            return new JsonObject();
        }
        String content = Files.readString(stateFile).trim();
        if (content.isEmpty()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static void writeBotStates(Path stateFile, JsonObject states) throws IOException {
        Files.writeString(stateFile, states.toString());
    }

    private static CommandSourceStack createAgentSourceFromPlayer(ServerPlayer player) {
        return player.createCommandSourceStack()
                .withPermission(4);
    }
}
