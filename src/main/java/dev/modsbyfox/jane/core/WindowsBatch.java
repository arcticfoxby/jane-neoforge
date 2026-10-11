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
                + "set \"JANE_RESTORE_LABEL=恢复点：\"\r\n"
                + "set \"JANE_INSTALLED=0\"\r\nset \"JANE_ROLLBACK_FAILED=0\"\r\n");
        bat.append("where fsutil.exe >nul 2>nul\r\nif errorlevel 1 goto unsafe\r\n");
        for (String directory : List.of("jane", "jane\\pending", pending)) {
            bat.append("call :safe_dir \"").append(directory).append("\"\r\nif errorlevel 1 goto unsafe\r\n");
        }
        for (String file : List.of(log, pending + "\\pid.txt")) {
            bat.append("fsutil reparsepoint query \"").append(file)
                    .append("\" >nul 2>nul\r\nif not errorlevel 1 goto unsafe\r\n");
        }
        bat.append("> \"").append(log).append("\" echo SYNC ").append(plan.syncId())
                .append(" SERVER ").append(plan.serverId())
                .append("\r\nif not exist \"").append(log).append("\" goto unsafe\r\n")
                .append("fsutil reparsepoint query \"").append(log)
                .append("\" >nul 2>nul\r\nif not errorlevel 1 goto unsafe\r\n");
        bat.append("echo ================================\r\necho  Jane - 模组更新助手\r\necho ================================\r\n");
        bat.append("echo 正在等待 Minecraft 关闭...\r\n:wait\r\n");
        bat.append("tasklist /FI \"PID eq ").append(pid).append("\" /FO CSV /NH > \"").append(pending).append("\\pid.txt\" 2>> \"").append(log).append("\"\r\n");
        bat.append("if errorlevel 1 goto failure\r\n");
        bat.append("findstr /C:\"").append(pid).append("\" \"").append(pending).append("\\pid.txt\" >nul\r\n");
        bat.append("if errorlevel 1 goto begin_update\r\ntimeout /t 1 /nobreak >nul\r\ngoto wait\r\n");
        bat.append(":begin_update\r\necho Minecraft 已关闭。\r\n");
        bat.append("where certutil.exe >nul 2>nul\r\nif errorlevel 1 goto failure\r\n")
                .append("where fsutil.exe >nul 2>nul\r\nif errorlevel 1 goto failure\r\n");
        for (String directory : List.of("mods", "jane", "jane\\pending", pending,
                "jane\\staging", staging)) {
            bat.append("call :safe_dir \"").append(directory).append("\"\r\nif errorlevel 1 goto failure\r\n");
        }
        for (String directory : List.of("jane\\backups", "jane\\backups\\" + plan.serverId())) {
            bat.append("call :ensure_dir \"").append(directory).append("\"\r\nif errorlevel 1 goto failure\r\n");
        }
        bat.append("call :target_absent \"").append(backup).append("\"\r\nif errorlevel 1 goto failure\r\n")
                .append("md \"").append(backup).append("\" 2>> \"").append(log)
                .append("\"\r\nif errorlevel 1 goto failure\r\n")
                .append("call :safe_dir \"").append(backup).append("\"\r\nif errorlevel 1 goto failure\r\n")
                .append("call :target_absent \"").append(backup).append("\\backup.json\"\r\n")
                .append("if errorlevel 1 goto failure\r\n")
                .append("copy /-Y /V \"").append(pending).append("\\backup.json\" \"")
                .append(backup).append("\\backup.json\" <nul >nul 2>> \"").append(log)
                .append("\"\r\nif errorlevel 1 goto failure\r\n");
        List<UpdatePlan.Operation> ops = plan.operations();
        for (int i = 0; i < ops.size(); i++) {
            UpdatePlan.Operation op = ops.get(i);
            if (op.kind() == UpdatePlan.Kind.REPLACE) {
                bat.append("echo 正在备份旧模组... ").append(i + 1).append("/").append(ops.size()).append("\r\n");
                bat.append("call :verify_jar \"mods\\").append(op.oldFile()).append("\" ")
                        .append(op.oldHash()).append(" -\r\nif errorlevel 1 goto rollback_backup\r\n")
                        .append("call :target_absent \"").append(backup).append("\\")
                        .append(op.oldFile()).append("\"\r\nif errorlevel 1 goto rollback_backup\r\n")
                        .append("move /-Y \"mods\\").append(op.oldFile()).append("\" \"")
                        .append(backup).append("\\").append(op.oldFile())
                        .append("\" <nul >nul 2>> \"").append(log)
                        .append("\"\r\nif errorlevel 1 goto rollback_backup\r\n")
                        .append("call :target_absent \"mods\\").append(op.oldFile())
                        .append("\"\r\nif errorlevel 1 goto rollback_backup\r\n")
                        .append("call :verify_jar \"").append(backup).append("\\")
                        .append(op.oldFile()).append("\" ").append(op.oldHash())
                        .append(" -\r\nif errorlevel 1 goto rollback_backup\r\n");
                bat.append(">> \"").append(log).append("\" echo BACKUP ").append(op.modId()).append(" OK\r\n");
            }
        }
        for (int i = 0; i < ops.size(); i++) {
            UpdatePlan.Operation op = ops.get(i);
            String temporary = staging + "\\install-" + i + ".part";
            bat.append("echo 正在安装新模组... ").append(i + 1).append("/").append(ops.size()).append("\r\n");
            bat.append("call :verify_jar \"").append(staging).append("\\").append(op.newFile())
                    .append("\" ").append(op.newHash()).append(' ').append(op.size())
                    .append("\r\nif errorlevel 1 goto rollback\r\n")
                    .append("call :target_absent \"").append(temporary)
                    .append("\"\r\nif errorlevel 1 goto rollback\r\n")
                    .append("copy /-Y /V \"").append(staging).append("\\").append(op.newFile())
                    .append("\" \"").append(temporary)
                    .append("\" <nul >nul 2>> \"").append(log)
                    .append("\"\r\nif errorlevel 1 goto rollback\r\n")
                    .append("call :verify_jar \"").append(temporary).append("\" ")
                    .append(op.newHash()).append(' ').append(op.size())
                    .append("\r\nif errorlevel 1 goto rollback\r\n")
                    .append("call :target_absent \"mods\\").append(op.newFile())
                    .append("\"\r\nif errorlevel 1 goto rollback\r\n")
                    .append("move /-Y \"").append(temporary).append("\" \"mods\\")
                    .append(op.newFile()).append("\" <nul >nul 2>> \"").append(log)
                    .append("\"\r\nif errorlevel 1 goto rollback\r\n")
                    .append("set \"JANE_INSTALLED=").append(i + 1).append("\"\r\n")
                    .append("call :verify_jar \"mods\\").append(op.newFile()).append("\" ")
                    .append(op.newHash()).append(' ').append(op.size())
                    .append("\r\nif errorlevel 1 goto rollback\r\n");
            bat.append(">> \"").append(log).append("\" echo INSTALL ").append(op.modId()).append(" OK\r\n");
        }
        bat.append("call :target_absent \"").append(pending)
                .append("\\success.marker\"\r\nif errorlevel 1 goto rollback\r\n")
                .append("> \"").append(pending)
                .append("\\success.marker\" echo SUCCESS\r\nif errorlevel 1 goto rollback\r\n");
        bat.append(">> \"").append(log).append("\" echo SUCCESS\r\n");
        bat.append("echo 更新完成。\r\necho %JANE_RESTORE_LABEL%").append(plan.timestamp())
                .append("\r\necho.\r\necho 请您手动重启客户端。\r\necho.\r\necho 按任意键退出...\r\npause >nul\r\nexit /b 0\r\n");
        bat.append(":rollback\r\necho 更新失败，正在恢复原文件...\r\n");
        bat.append("if exist \"").append(pending).append("\\success.marker\" del /F /Q \"")
                .append(pending).append("\\success.marker\" >nul 2>> \"").append(log).append("\"\r\n");
        bat.append(">> \"").append(log).append("\" echo ROLLBACK START\r\n");
        for (int i = ops.size() - 1; i >= 0; i--) {
            UpdatePlan.Operation op = ops.get(i);
            bat.append("if %JANE_INSTALLED% LSS ").append(i + 1).append(" goto rollback_install_next_")
                    .append(i).append("\r\n")
                    .append("call :verify_jar \"mods\\").append(op.newFile()).append("\" ")
                    .append(op.newHash()).append(' ').append(op.size())
                    .append("\r\nif errorlevel 1 goto rollback_install_bad_").append(i).append("\r\n")
                    .append("del /F /Q \"mods\\").append(op.newFile())
                    .append("\" >> \"").append(log).append("\" 2>&1\r\n")
                    .append("if errorlevel 1 goto rollback_install_bad_").append(i).append("\r\n")
                    .append("goto rollback_install_next_").append(i).append("\r\n")
                    .append(":rollback_install_bad_").append(i).append("\r\n")
                    .append("set \"JANE_ROLLBACK_FAILED=1\"\r\n")
                    .append(":rollback_install_next_").append(i).append("\r\n");
        }
        for (int i = ops.size() - 1; i >= 0; i--) {
            UpdatePlan.Operation op = ops.get(i);
            String temporary = staging + "\\install-" + i + ".part";
            bat.append("if not exist \"").append(temporary).append("\" goto rollback_part_next_")
                    .append(i).append("\r\n")
                    .append("call :verify_jar \"").append(temporary).append("\" ")
                    .append(op.newHash()).append(' ').append(op.size())
                    .append("\r\nif errorlevel 1 goto rollback_part_bad_").append(i).append("\r\n")
                    .append("del /F /Q \"").append(temporary).append("\" >nul 2>nul\r\n")
                    .append("if errorlevel 1 goto rollback_part_bad_").append(i).append("\r\n")
                    .append("goto rollback_part_next_").append(i).append("\r\n")
                    .append(":rollback_part_bad_").append(i).append("\r\n")
                    .append("set \"JANE_ROLLBACK_FAILED=1\"\r\n")
                    .append(":rollback_part_next_").append(i).append("\r\n");
        }
        bat.append(":rollback_backup\r\n");
        for (int i = ops.size() - 1; i >= 0; i--) {
            UpdatePlan.Operation op = ops.get(i);
            if (op.kind() == UpdatePlan.Kind.REPLACE) {
                bat.append("if not exist \"").append(backup).append("\\").append(op.oldFile())
                        .append("\" goto rollback_backup_next_").append(i).append("\r\n")
                        .append("call :verify_jar \"").append(backup).append("\\")
                        .append(op.oldFile()).append("\" ").append(op.oldHash())
                        .append(" -\r\nif errorlevel 1 goto rollback_backup_bad_").append(i).append("\r\n")
                        .append("call :target_absent \"mods\\").append(op.oldFile())
                        .append("\"\r\nif errorlevel 1 goto rollback_backup_bad_").append(i).append("\r\n")
                        .append("move /-Y \"").append(backup).append("\\").append(op.oldFile())
                        .append("\" \"mods\\").append(op.oldFile())
                        .append("\" <nul >> \"").append(log).append("\" 2>&1\r\n")
                        .append("if errorlevel 1 goto rollback_backup_bad_").append(i).append("\r\n")
                        .append("goto rollback_backup_next_").append(i).append("\r\n")
                        .append(":rollback_backup_bad_").append(i).append("\r\n")
                        .append("set \"JANE_ROLLBACK_FAILED=1\"\r\n")
                        .append(":rollback_backup_next_").append(i).append("\r\n");
            }
        }
        bat.append(":failure\r\ncall :target_absent \"").append(pending)
                .append("\\failed.marker\"\r\nif errorlevel 1 goto failed_log\r\n")
                .append("> \"").append(pending).append("\\failed.marker\" echo FAILED\r\n")
                .append(":failed_log\r\n");
        bat.append(">> \"").append(log).append("\" echo FAILED\r\n");
        bat.append("echo 更新失败。请重新启动 Minecraft 查看详细信息。\r\n")
                .append("if \"%JANE_ROLLBACK_FAILED%\"==\"1\" echo 回滚未完成，请检查 mods 与 Jane 备份。\r\n")
                .append("echo.\r\necho 按任意键退出...\r\npause >nul\r\nexit /b 1\r\n")
                .append(":unsafe\r\nexit /b 1\r\n");
        appendSafetySubroutines(bat);
        return bat.toString();
    }

    /** Shared post-exit guards for generated CMD helpers. */
    static void appendSafetySubroutines(StringBuilder bat) {
        bat.append(":safe_dir\r\nif not exist \"%~1\\\" exit /b 1\r\n")
                .append("fsutil reparsepoint query \"%~1\" >nul 2>nul\r\n")
                .append("if not errorlevel 1 exit /b 1\r\nexit /b 0\r\n")
                .append(":ensure_dir\r\nif exist \"%~1\\\" goto ensure_dir_check\r\n")
                .append("call :target_absent \"%~1\"\r\nif errorlevel 1 exit /b 1\r\n")
                .append("md \"%~1\"\r\nif errorlevel 1 exit /b 1\r\n")
                .append(":ensure_dir_check\r\ncall :safe_dir \"%~1\"\r\nexit /b %errorlevel%\r\n")
                .append(":target_absent\r\nif exist \"%~1\" exit /b 1\r\n")
                .append("fsutil reparsepoint query \"%~1\" >nul 2>nul\r\n")
                .append("if not errorlevel 1 exit /b 1\r\nexit /b 0\r\n")
                .append(":verify_jar\r\nif not exist \"%~1\" exit /b 1\r\n")
                .append("fsutil reparsepoint query \"%~1\" >nul 2>nul\r\n")
                .append("if not errorlevel 1 exit /b 1\r\n")
                .append("if not \"%~3\"==\"-\" if not \"%~z1\"==\"%~3\" exit /b 1\r\n")
                .append("set \"JANE_HASH=\"\r\n")
                .append("for /f \"skip=1 tokens=*\" %%H in ('certutil -hashfile \"%~1\" SHA512 2^>nul') do if not defined JANE_HASH set \"JANE_HASH=%%H\"\r\n")
                .append("set \"JANE_HASH=%JANE_HASH: =%\"\r\n")
                .append("if /I not \"%JANE_HASH%\"==\"%~2\" exit /b 1\r\nexit /b 0\r\n");
    }
}
