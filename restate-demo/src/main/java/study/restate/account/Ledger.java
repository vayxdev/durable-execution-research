package study.restate.account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 跨进程的副作用账本(同 Temporal 版思路)。崩溃恢复演示时进程会被杀重启,
 * 用这个磁盘文件来证明:已 journal 的步骤恢复后不会被重复执行。
 */
public final class Ledger {

    static final Path FILE = Path.of("/tmp", "durable-ledger-restate.log");

    private Ledger() {}

    static void record(String line) {
        try {
            Files.writeString(FILE, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
