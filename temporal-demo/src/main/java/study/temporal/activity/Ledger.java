package study.temporal.activity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 把「真正执行过的副作用」追加到磁盘文件。
 *
 * 为什么需要它:演示崩溃恢复时,执行进程会被杀掉重启,内存里的余额状态会丢失,
 * 无法作为证据。而这个文件跨进程存活 —— 如果 withdraw 因恢复被重复执行,文件里
 * 就会出现两行;只有一行就证明「每步只执行一次」。
 */
public final class Ledger {

    static final Path FILE = Path.of("/tmp", "durable-ledger-temporal.log");

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
