package top.weixiansen574.hybridfilexfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

public class Strings {

    private static final ResourceBundle messages;

    static {
        messages = ResourceBundle.getBundle("messages", Locale.getDefault(), new ResourceBundle.Control() {
            @Override
            public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
                    throws IOException {
                String bundleName = toBundleName(baseName, locale);
                String resourceName = toResourceName(bundleName, "properties");
                try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                    if (stream == null) {
                        return null;
                    }
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        return new PropertyResourceBundle(reader);
                    }
                }
            }
        });
    }

    public static String get(String key) {
        return messages.getString(key);
    }

    public static String get(String key, Object... args) {
        String message = messages.getString(key);
        return String.format(message, args);
    }

    public static void printf(String key) {
        System.out.println(get(key));
    }

    public static void printf(String key, Object... args) {
        System.out.println(get(key, args));
    }
}
