package dev.modsbyfox.jane.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Transactional Windows helper for moving selected local JARs out of mods after JVM exit. */
public final class ClientDisableBatch {
    private ClientDisableBatch() { }

    public static Path write(Path gameDir, ClientDisablePlan plan, long pid) throws IOException {
        Path parent = PathSafety.janeDirectory(gameDir, "disabled", plan.serverId());
        Path destination = parent.resolve(plan.timestamp());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Disable target already exists");
        Files.createDirectory(destination);
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("serverId", plan.serverId());
        json.addProperty("timestamp", plan.timestamp());
        JsonArray entries = new JsonArray();
        for (ClientDisablePlan.Entry entry : plan.entries()) {
            JsonObject item = new JsonObject();
            item.addProperty("modId", entry.modId());
            item.addProperty("displayName", entry.displayName());
            item.addProperty("version", entry.version());
            item.addProperty("filename", entry.filename());
            item.addProperty("sha512", entry.sha512());
            entries.add(item);
        }
        json.add("entries", entries);
        Files.writeString(destination.resolve("disabled.json"), json.toString(), StandardCharsets.UTF_8);
        Path script = destination.resolve("disable.bat");
        Files.writeString(script, generate(plan, pid), StandardCharsets.UTF_8);
        return script;
    }

    public static String generate(ClientDisablePlan plan, long pid) {
        if (pid <= 0) throw new IllegalArgumentException("Invalid Minecraft PID");
        String folder = "jane\\disabled\\" + plan.serverId() + "\\" + plan.timestamp();
        String log = folder + "\\disable.log";
        // Keep Chinese console text near the start. Long UTF-8 batches otherwise risk CMD misparsing late lines.
        StringBuilder bat = new StringBuilder("@echo off\r\nchcp 65001 >nul\r\nsetlocal DisableDelayedExpansion\r\n"
                + "set \"JANE_MOVED=0\"\r\nset \"JANE_ROLLBACK_FAILED=0\"\r\n"
                + "set \"JANE_TITLE=Jane - 客户端 Mod 禁用助手\"\r\n"
                + "set \"JANE_WAIT=正在等待 Minecraft 关闭...\"\r\n"
                + "set \"JANE_CLOSED=Minecraft 已关闭。\"\r\n"
                + "set \"JANE_PROGRESS=正在禁用客户端 Mod...\"\r\n"
                + "set \"JANE_DONE=禁用完成\"\r\n"
                + "set \"JANE_COUNT=已禁用：\"\r\n"
                + "set \"JANE_COUNT_SUFFIX=个客户端 Mod\"\r\n"
                + "set \"JANE_BACKUP=禁用备份点：\"\r\n"
                + "set \"JANE_SAVED=文件已安全保存至：\"\r\n"
                + "set \"JANE_NOT_DELETED=Jane 没有永久删除这些文件。\"\r\n"
                + "set \"JANE_RESTORE=如需恢复，请关闭 Minecraft 后将所需 JAR 移回 mods。\"\r\n"
                + "set \"JANE_RESTART=请手动重新启动客户端。\"\r\n"
                + "set \"JANE_EXIT=按任意键退出...\"\r\n"
                + "set \"JANE_ROLLING_BACK=禁用失败，正在恢复原文件...\"\r\n"
                + "set \"JANE_NOT_DONE=禁用未完成\"\r\n"
                + "set \"JANE_RESTORE_ATTEMPTED=已移动的文件已尝试恢复。\"\r\n"
                + "set \"JANE_CHECK=请检查 mods 和 jane\\disabled 中的文件。\"\r\n"
                + "set \"JANE_ROLLBACK_ERROR=恢复过程遇到错误，请检查 mods 与 Jane disabled 目录。\"\r\n");
        bat.append("> \"").append(log).append("\" echo DISABLE ").append(plan.timestamp())
                .append(" SERVER ").append(plan.serverId()).append("\r\nif errorlevel 1 goto failure\r\n");
        bat.append("echo ================================\r\necho %JANE_TITLE%\r\necho ================================\r\n")
                .append("echo.\r\necho %JANE_WAIT%\r\n:wait\r\n");
        bat.append("tasklist /FI \"PID eq ").append(pid).append("\" /FO CSV /NH > \"")
                .append(folder).append("\\pid.txt\" 2>nul\r\nif errorlevel 1 goto failure\r\n");
        bat.append("findstr /C:\"").append(pid).append("\" \"").append(folder)
                .append("\\pid.txt\" >nul\r\nif errorlevel 1 goto begin_disable\r\n")
                .append("timeout /t 1 /nobreak >nul\r\ngoto wait\r\n:begin_disable\r\n")
                .append("echo %JANE_CLOSED%\r\necho.\r\n");
        for (int i = 0; i < plan.entries().size(); i++) {
            ClientDisablePlan.Entry entry = plan.entries().get(i);
            String name = entry.filename();
            bat.append("echo %JANE_PROGRESS% ").append(i + 1).append("/")
                    .append(plan.entries().size()).append("\r\n");
            bat.append("if not exist \"mods\\").append(name).append("\" goto rollback\r\n");
            bat.append("if exist \"").append(folder).append("\\").append(name).append("\" goto rollback\r\n");
            bat.append("move \"mods\\").append(name).append("\" \"").append(folder)
                    .append("\\").append(name).append("\" >nul 2>nul\r\nif errorlevel 1 goto rollback\r\n");
            bat.append("set \"JANE_MOVED=").append(i + 1).append("\"\r\n");
            bat.append(">> \"").append(log).append("\" echo MOVE ").append(entry.modId()).append(" OK\r\n");
        }
        bat.append("> \"").append(folder).append("\\success.marker\" echo SUCCESS\r\n")
                .append("if errorlevel 1 goto rollback\r\n")
                .append(">> \"").append(log).append("\" echo SUCCESS\r\n")
                .append("echo ================================\r\necho %JANE_DONE%\r\necho ================================\r\n")
                .append("echo.\r\necho %JANE_COUNT%").append(plan.entries().size()).append(" %JANE_COUNT_SUFFIX%\r\n")
                .append("echo %JANE_BACKUP%").append(plan.timestamp()).append("\r\necho.\r\n")
                .append("echo %JANE_SAVED%\r\necho jane\\disabled\\")
                .append(plan.serverId(), 0, 12).append("...\\").append(plan.timestamp()).append("\r\necho.\r\n")
                .append("echo %JANE_NOT_DELETED%\r\necho %JANE_RESTORE%\r\necho.\r\n")
                .append("echo %JANE_RESTART%\r\necho.\r\necho %JANE_EXIT%\r\npause >nul\r\nexit /b 0\r\n")
                .append(":rollback\r\necho %JANE_ROLLING_BACK%\r\n")
                .append("if exist \"").append(folder).append("\\success.marker\" del /F /Q \"")
                .append(folder).append("\\success.marker\" >nul 2>nul\r\n");
        for (int i = plan.entries().size() - 1; i >= 0; i--) {
            ClientDisablePlan.Entry entry = plan.entries().get(i);
            String name = entry.filename();
            bat.append("if %JANE_MOVED% LSS ").append(i + 1).append(" goto rollback_next_").append(i).append("\r\n")
                    .append("if exist \"mods\\").append(name).append("\" goto rollback_bad_").append(i).append("\r\n")
                    .append("if not exist \"").append(folder).append("\\").append(name)
                    .append("\" goto rollback_bad_").append(i).append("\r\n")
                    .append("move \"").append(folder).append("\\").append(name).append("\" \"mods\\")
                    .append(name).append("\" >nul 2>nul\r\nif errorlevel 1 goto rollback_bad_")
                    .append(i).append("\r\n")
                    .append(">> \"").append(log).append("\" echo ROLLBACK ").append(entry.modId())
                    .append(" OK\r\ngoto rollback_next_").append(i).append("\r\n")
                    .append(":rollback_bad_").append(i).append("\r\nset \"JANE_ROLLBACK_FAILED=1\"\r\n")
                    .append(">> \"").append(log).append("\" echo ROLLBACK ").append(entry.modId())
                    .append(" FAILED\r\n:rollback_next_").append(i).append("\r\n");
        }
        bat.append(":failure\r\n> \"").append(folder).append("\\failed.marker\" echo FAILED\r\n")
                .append(">> \"").append(log).append("\" echo FAILED\r\n")
                .append("echo ================================\r\necho %JANE_NOT_DONE%\r\necho ================================\r\n")
                .append("echo %JANE_RESTORE_ATTEMPTED%\r\necho %JANE_CHECK%\r\n")
                .append("if \"%JANE_ROLLBACK_FAILED%\"==\"1\" echo %JANE_ROLLBACK_ERROR%\r\n")
                .append("echo.\r\necho %JANE_EXIT%\r\npause >nul\r\nexit /b 1\r\n");
        return bat.toString();
    }
}
