package top.weixiansen574.hybridfilexfer;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight pure-Java Windows 11 WinRT Toast fallback for {@code :pc-shell} when launched
 * directly via {@code java -jar hfx-shell.jar} without the C# {@code HybridFileXferLauncher}.
 *
 * <p>Strictly obeys the anti-crop Toast XML iron rule: NEVER uses {@code placement="hero"};
 * always uses inline {@code <image src="file:///..." hint-crop="none"/>} inside
 * {@code <binding template="ToastGeneric">}, and never prints ASCII console QR codes.</p>
 */
public final class WinToastFallback {

    private static final String POWERSHELL_APP_ID =
            "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe";

    private WinToastFallback() {
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    /**
     * Builds the anti-crop Windows 11 Toast XML for an inline 480x480 QR PNG.
     */
    public static String buildQrToastXml(String qrPngPath, String hostName, List<String> ips, int ttlSeconds) {
        URI fileUri = new File(qrPngPath).getAbsoluteFile().toURI();
        String src = fileUri.toASCIIString().replace("file:/", "file:///").replace("file:////", "file:///");
        String attribution = (ips != null && !ips.isEmpty())
                ? String.join(" · ", ips)
                : (hostName != null ? hostName : "PC");
        int ttl = ttlSeconds > 0 ? ttlSeconds : 120;

        return "<toast duration=\"long\" scenario=\"reminder\">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>HybridFileXfer · 扫码绑定此电脑</text>"
                + "<text>请用手机端「多轨快传」扫描下方二维码（" + ttl + " 秒内有效）</text>"
                + "<image src=\"" + escapeXml(src) + "\" hint-crop=\"none\"/>"
                + "<text placement=\"attribution\">" + escapeXml(attribution) + "</text>"
                + "</binding></visual>"
                + "</toast>";
    }

    public static String buildStatusToastXml(String title, String body) {
        return "<toast duration=\"short\">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>" + escapeXml(title) + "</text>"
                + "<text>" + escapeXml(body) + "</text>"
                + "</binding></visual>"
                + "</toast>";
    }

    /**
     * Best-effort push of a Toast XML on Windows via PowerShell WinRT API.
     */
    public static boolean showToastOnWindows(String toastXml) {
        if (!isWindows() || toastXml == null || toastXml.isEmpty()) {
            return false;
        }
        String xmlB64 = Base64.getEncoder().encodeToString(toastXml.getBytes(StandardCharsets.UTF_8));
        String script =
                "$ErrorActionPreference='Stop';"
                        + "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null;"
                        + "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null;"
                        + "$xmlStr=[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + xmlB64 + "'));"
                        + "$doc=New-Object Windows.Data.Xml.Dom.XmlDocument;"
                        + "$doc.LoadXml($xmlStr);"
                        + "$toast=New-Object Windows.UI.Notifications.ToastNotification $doc;"
                        + "$notifier=[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('" + POWERSHELL_APP_ID + "');"
                        + "$notifier.Show($toast);";
        try {
            Process p = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                    .redirectErrorStream(true)
                    .start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException | IllegalThreadStateException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
