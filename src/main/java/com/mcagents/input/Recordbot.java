package com.mcagents.input;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public class Recordbot {
    private static final String RECORD_FILE_NAME = "agent_records.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("agent")
                                .then(Commands.literal("record")
                                        .then(Commands.argument("bot_name", StringArgumentType.word())
                                                .then(Commands.argument("tag", StringArgumentType.greedyString())
                                                        .executes(Recordbot::handleRecordCommand))))
                                .then(Commands.literal("botlist")
                                        .executes(Recordbot::handleBotListCommand))
                                .then(Commands.literal("reload")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(Recordbot::handleReloadCommand))
                )
        );
    }

    private static int handleRecordCommand(CommandContext<CommandSourceStack> context) {
        String botName = StringArgumentType.getString(context, "bot_name");
        String tag = StringArgumentType.getString(context, "tag");

        try {
            Path dataFile = getDataFile(context.getSource().getServer());
            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonArray records = readRecords(dataFile);

            JsonObject record = new JsonObject();
            record.addProperty("bot_name", botName);
            record.addProperty("tag", tag);
            records.add(record);

            Files.writeString(
                    dataFile,
                    GSON.toJson(records),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            context.getSource().sendSuccess(
                    () -> Component.translatable("command.modid.agent.record.success", botName, tag),
                    false
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    Component.translatable("command.modid.agent.record.error", e.getMessage())
            );
            return 0;
        }
    }

    private static int handleBotListCommand(CommandContext<CommandSourceStack> context) {
        try {
            JsonArray records = readRecords(getDataFile(context.getSource().getServer()));

            if (records.isEmpty()) {
                context.getSource().sendSuccess(
                        () -> Component.translatable("command.modid.agent.botlist.empty"),
                        false
                );
                return 1;
            }

            context.getSource().sendSuccess(
                    () -> Component.translatable("command.modid.agent.botlist.header"),
                    false
            );

            for (JsonElement element : records) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject record = element.getAsJsonObject();
                String botName = record.has("bot_name") ? record.get("bot_name").getAsString() : "unknown";
                String tag = record.has("tag") ? record.get("tag").getAsString() : "unknown";

                context.getSource().sendSuccess(
                        () -> Component.translatable("command.modid.agent.botlist.item", botName, tag),
                        false
                );
            }
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    Component.translatable("command.modid.agent.botlist.error", e.getMessage())
            );
            return 0;
        }
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

    private static int handleReloadCommand(CommandContext<CommandSourceStack> context) {
        try {
            Agent.reloadConfig(context.getSource().getServer());
            context.getSource().sendSuccess(
                    () -> Component.translatable("command.modid.agent.reload.success"),
                    true
            );
            return 1;
        } catch (IOException e) {
            context.getSource().sendFailure(
                    Component.translatable("command.modid.agent.reload.error", e.getMessage())
            );
            return 0;
        }
    }

    private static Path getDataFile(MinecraftServer server) throws IOException {
        Path dataDir = Agent.getAgentDataDirectory(server);
        Files.createDirectories(dataDir);

        Path dataFile = dataDir.resolve(RECORD_FILE_NAME);
        if (Files.exists(dataFile)) {
            return dataFile;
        }

        Path legacyAgentsDataFile = dataDir.getParent() != null
                ? dataDir.getParent().resolve("agentsdata").resolve(RECORD_FILE_NAME)
                : dataDir.resolveSibling("agentsdata").resolve(RECORD_FILE_NAME);
        if (Files.exists(legacyAgentsDataFile)) {
            Files.copy(legacyAgentsDataFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            return dataFile;
        }

        Path legacyFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(RECORD_FILE_NAME);
        if (Files.exists(legacyFile)) {
            Files.copy(legacyFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return dataFile;
    }
}
