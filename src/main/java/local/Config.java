package local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {

    private static final Properties PROPS = new Properties();

    static {
        Path file = Path.of("config.properties");   // relative to where you run mvn
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                PROPS.load(in);
            } catch (IOException e) {
                System.err.println("[config] could not read config.properties");
            }
        }
    }

    private Config() {}

    public static String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;
        String v = PROPS.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
