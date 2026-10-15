package dev.litetook.sockseek.process;

import java.util.List;

/**
 * A program for the process tests to drive: exits with a code, or sleeps.
 *
 * <p>The JVM running the tests starts it, so the tests need nothing installed and behave the
 * same on every machine.
 */
public final class Child {

    private Child() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 2 && args[0].equals("sleep")) {
            Thread.sleep(Long.parseLong(args[1]));
            return;
        }
        System.exit(args.length == 2 && args[0].equals("exit") ? Integer.parseInt(args[1]) : 0);
    }

    /** The command that runs this class in a new JVM. */
    static List<String> command(String what, String value) {
        String java = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        return List.of(java, "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), what, value);
    }
}
