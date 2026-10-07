package dev.modsbyfox.jane.core;

import java.util.List;

/** Generates only literal, locally validated relative paths. No server text is emitted. */
public final class WindowsBatch {
    private WindowsBatch() { }

    public static String generate(UpdatePlan plan, long pid) {
        if (pid <= 0) throw new IllegalArgumentException("Invalid Minecraft PID");
        String pending = "jane\\pending\\" + plan.syncId();
        String staging = "jane\\staging\\" + plan.syncId();
        String backup = "jane\\backups\\" + plan.serverId() + "\\" + plan.timestamp();
        String log = pending + "\\update.log";
        // CMD can misparse a UTF-8 command near the end of a long batch; define its label early.
        StringBuilder bat = new StringBuilder("@echo off\r\nchcp 65001 >nul\r\nsetlocal DisableDelayedExpansion\r\n"
                + "set \"JANE_RESTORE_LABEL=恢复点：\"\r\n");
        bat.append("> \"").append(log).append("\" echo SYNC ").append(plan.syncId()).append(" SERVER ").append(plan.serverId()).append("\r\n");
        bat.append("echo ================================\r\necho  Jane - 模组更新助手\r\necho ================================\r\n");
        bat.append("echo 正在等待 Minecraft 关闭...\r\n:wait\r\n");
        bat.append("tasklist /FI \"PID eq ").append(pid).append("\" /FO CSV /NH > \"").append(pending).append("\\pid.txt\" 2>> \"").append(log).append("\"\r\n");
        bat.append("if errorlevel 1 goto failure\r\n");
        bat.append("findstr /C:\"").append(pid).append("\" \"").append(pending).append("\\pid.txt\" >nul\r\n");
        bat.append("if errorlevel 1 goto begin_update\r\ntimeout /t 1 /nobreak >nul\r\ngoto wait\r\n");
        bat.append(":begin_update\r\necho Minecraft 已关闭。\r\n");
        bat.append("md \"").append(backup).append("\" 2>> \"").append(log).append("\"\r\nif errorlevel 1 goto failure\r\n");
        bat.append("copy /Y \"").append(pending).append("\\backup.json\" \"").append(backup).append("\\backup.json\" >nul 2>> \"").append(log).append("\"\r\nif errorlevel 1 goto failure\r\n");
        List<UpdatePlan.Operation> ops = plan.operations();
        for (int i = 0; i < ops.size(); i++) {
            UpdatePlan.Operation op = ops.get(i);
            if (op.kind() == UpdatePlan.Kind.REPLACE) {
                bat.append("echo 正在备份旧模组... ").append(i + 1).append("/").append(ops.size()).append("\r\n");
                bat.append("move /Y \"mods\\").append(op.oldFile()).append("\" \"").append(backup).append("\\").append(op.oldFile())
                        .append("\" >nul 2>> \"").append(log).append("\"\r\nif errorlevel 1 goto rollback_backup\r\n");
                bat.append(">> \"").append(log).append("\" echo BACKUP ").append(op.modId()).append(" OK\r\n");
            }
        }
        for (int i = 0; i < ops.size(); i++) {
            UpdatePlan.Operation op = ops.get(i);
            bat.append("echo 正在安装新模组... ").append(i + 1).append("/").append(ops.size()).append("\r\n");
            bat.append("copy /Y /V \"").append(staging).append("\\").append(op.newFile()).append("\" \"mods\\")
                    .append(op.newFile()).append("\" >nul 2>> \"").append(log).append("\"\r\nif errorlevel 1 goto rollback\r\n");
            bat.append(">> \"").append(log).append("\" echo INSTALL ").append(op.modId()).append(" OK\r\n");
        }
        bat.append("> \"").append(pending).append("\\success.marker\" echo SUCCESS\r\nif errorlevel 1 goto rollback\r\n");
        bat.append(">> \"").append(log).append("\" echo SUCCESS\r\n");
        bat.append("echo 更新完成。\r\necho %JANE_RESTORE_LABEL%").append(plan.timestamp())
                .append("\r\necho.\r\necho 请您手动重启客户端。\r\necho.\r\necho 按任意键退出...\r\npause >nul\r\nexit /b 0\r\n");
        bat.append(":rollback\r\necho 更新失败，正在恢复原文件...\r\n");
        bat.append("if exist \"").append(pending).append("\\success.marker\" del /F /Q \"")
                .append(pending).append("\\success.marker\" >nul 2>> \"").append(log).append("\"\r\n");
        bat.append(">> \"").append(log).append("\" echo ROLLBACK START\r\n");
        for (UpdatePlan.Operation op : ops) {
            bat.append("if exist \"mods\\").append(op.newFile()).append("\" del /F /Q \"mods\\")
                    .append(op.newFile()).append("\" >> \"").append(log).append("\" 2>&1\r\n");
        }
        bat.append(":rollback_backup\r\n");
        for (UpdatePlan.Operation op : ops) {
            if (op.kind() == UpdatePlan.Kind.REPLACE) {
                bat.append("if exist \"").append(backup).append("\\").append(op.oldFile()).append("\" move /Y \"")
                        .append(backup).append("\\").append(op.oldFile()).append("\" \"mods\\")
                        .append(op.oldFile()).append("\" >> \"").append(log).append("\" 2>&1\r\n");
            }
        }
        bat.append(":failure\r\n> \"").append(pending).append("\\failed.marker\" echo FAILED\r\n");
        bat.append(">> \"").append(log).append("\" echo FAILED\r\n");
        bat.append("echo 更新失败。请重新启动 Minecraft 查看详细信息。\r\necho.\r\necho 按任意键退出...\r\npause >nul\r\nexit /b 1\r\n");
        return bat.toString();
    }
}
