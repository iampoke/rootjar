package rootjar.console;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class ConsoleOutput {
    private static final Object LOCK = new Object();
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);

    private ConsoleOutput() {}

    public static void line(String prefix, String text) {
        synchronized (LOCK) {
            OUT.print('[');
            OUT.print(prefix);
            OUT.print("] ");
            OUT.print(text);
            OUT.print('\n');
            OUT.flush();
        }
    }

    public static void root(String text) {
        line("root", text);
    }
}