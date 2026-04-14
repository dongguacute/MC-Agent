package com.mcagents.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Bridges {@link CommandSourceStack} permission APIs across Minecraft versions:
 * older releases use numeric levels ({@code withPermission(int)} / {@code hasPermission(int)}),
 * while newer releases use {@link net.minecraft.server.permissions.PermissionSet}.
 */
public final class CommandSourcePermissions {
    private static final Method HAS_PERMISSION_INT;
    private static final Method WITH_PERMISSION_INT;
    private static final Method WITH_PERMISSION_SET;
    private static final Method COMMANDS_HAS_PERMISSION_CHECK;

    static {
        Method hasInt = null;
        Method withInt = null;
        Method withSet = null;
        Method commandsHas = null;
        try {
            hasInt = CommandSourceStack.class.getMethod("hasPermission", int.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            withInt = CommandSourceStack.class.getMethod("withPermission", int.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Class<?> permissionSet = Class.forName("net.minecraft.server.permissions.PermissionSet");
            withSet = CommandSourceStack.class.getMethod("withPermission", permissionSet);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Class<?> permissionCheck = Class.forName("net.minecraft.server.permissions.PermissionCheck");
            commandsHas = Commands.class.getMethod("hasPermission", permissionCheck);
        } catch (ReflectiveOperationException ignored) {
        }
        HAS_PERMISSION_INT = hasInt;
        WITH_PERMISSION_INT = withInt;
        WITH_PERMISSION_SET = withSet;
        COMMANDS_HAS_PERMISSION_CHECK = commandsHas;
    }

    private CommandSourcePermissions() {
    }

    public static CommandSourceStack withPermissionLevel(CommandSourceStack source, int level) {
        if (WITH_PERMISSION_INT != null) {
            try {
                return (CommandSourceStack) WITH_PERMISSION_INT.invoke(source, level);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (WITH_PERMISSION_SET != null) {
            try {
                Class<?> permissionLevelClass = Class.forName("net.minecraft.server.permissions.PermissionLevel");
                Object permissionLevel = permissionLevelClass.getMethod("byId", int.class).invoke(null, level);
                Class<?> levelBasedClass = Class.forName("net.minecraft.server.permissions.LevelBasedPermissionSet");
                Object permissionSet = levelBasedClass.getMethod("forLevel", permissionLevelClass).invoke(null, permissionLevel);
                return (CommandSourceStack) WITH_PERMISSION_SET.invoke(source, permissionSet);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("No compatible CommandSourceStack.withPermission API");
    }

    /**
     * Predicate matching vanilla {@code CommandSourceStack.hasPermission(level)} (minimum numeric level).
     */
    public static Predicate<CommandSourceStack> requiresPermissionLevel(int level) {
        return source -> {
            if (HAS_PERMISSION_INT != null) {
                try {
                    return (Boolean) HAS_PERMISSION_INT.invoke(source, level);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            }
            if (COMMANDS_HAS_PERMISSION_CHECK != null) {
                try {
                    Object check = Commands.class.getField(permissionFieldForLevel(level)).get(null);
                    @SuppressWarnings("unchecked")
                    Predicate<CommandSourceStack> predicate = (Predicate<CommandSourceStack>)
                            COMMANDS_HAS_PERMISSION_CHECK.invoke(null, check);
                    return predicate.test(source);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            }
            return false;
        };
    }

    /**
     * Maps vanilla numeric permission level (0–4) to {@link Commands} LEVEL_* fields on newer game versions.
     */
    private static String permissionFieldForLevel(int level) {
        return switch (Math.min(4, Math.max(0, level))) {
            case 0 -> "LEVEL_ALL";
            case 1 -> "LEVEL_MODERATORS";
            case 2 -> "LEVEL_GAMEMASTERS";
            case 3 -> "LEVEL_ADMINS";
            default -> "LEVEL_OWNERS";
        };
    }
}
