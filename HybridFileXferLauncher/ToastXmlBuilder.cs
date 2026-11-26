using System.Xml.Linq;

namespace HybridFileXferLauncher;

/// <summary>
/// Pure-XML builder for Windows 11 native Toast notifications.
///
/// IRON RULE: NEVER emits placement="hero" (Windows 11 crops hero images to ~2:1 landscape,
/// destroying QR finder patterns). Always uses inline &lt;image src="file:///..." hint-crop="none"/&gt;
/// inside &lt;binding template="ToastGeneric"&gt;.
/// </summary>
public static class ToastXmlBuilder
{
    public const string DefaultAumid = "HybridFileXfer.Desktop";
    public const string PairQrTag = "hfx_pair_qr";
    public const string PairQrGroup = "hfx_pairing";
    public const string StatusTag = "hfx_status";
    public const string StatusGroup = "hfx_status";

    public static string ToFileUri(string localPath)
    {
        if (string.IsNullOrWhiteSpace(localPath))
        {
            throw new ArgumentException("PNG path must not be empty", nameof(localPath));
        }
        var fullPath = Path.GetFullPath(localPath);
        return new Uri(fullPath).AbsoluteUri;
    }

    public static string BuildQrPairingToastXml(
        string qrPngPath,
        string hostName,
        IReadOnlyList<string> ips,
        int ttlSeconds = 120)
    {
        var fileUri = ToFileUri(qrPngPath);
        var effectiveTtl = ttlSeconds > 0 ? ttlSeconds : 120;
        var attribution = ips is { Count: > 0 }
            ? string.Join(" · ", ips)
            : hostName;

        var openArgs = $"action=open_png&path={Uri.EscapeDataString(Path.GetFullPath(qrPngPath))}";
        var cancelArgs = "action=cancel_pair";

        var binding = new XElement("binding",
            new XAttribute("template", "ToastGeneric"),
            new XElement("text", "HybridFileXfer · 扫码绑定此电脑"),
            new XElement("text", $"请用手机端「多轨快传」扫描下方二维码（{effectiveTtl} 秒内有效）"),
            new XElement("image",
                new XAttribute("src", fileUri),
                new XAttribute("hint-crop", "none")),
            new XElement("text",
                new XAttribute("placement", "attribution"),
                attribution));

        var actions = new XElement("actions",
            new XElement("action",
                new XAttribute("content", "放大查看"),
                new XAttribute("arguments", openArgs),
                new XAttribute("activationType", "foreground")),
            new XElement("action",
                new XAttribute("content", "取消"),
                new XAttribute("arguments", cancelArgs),
                new XAttribute("activationType", "background")));

        var toast = new XElement("toast",
            new XAttribute("duration", "long"),
            new XAttribute("scenario", "reminder"),
            new XAttribute("launch", openArgs),
            new XElement("visual", binding),
            actions);

        return toast.ToString(SaveOptions.DisableFormatting);
    }

    public static string BuildStatusToastXml(string title, string message, string? attribution = null)
    {
        var binding = new XElement("binding",
            new XAttribute("template", "ToastGeneric"),
            new XElement("text", title),
            new XElement("text", message));

        if (!string.IsNullOrWhiteSpace(attribution))
        {
            binding.Add(new XElement("text",
                new XAttribute("placement", "attribution"),
                attribution));
        }

        var toast = new XElement("toast",
            new XAttribute("duration", "short"),
            new XElement("visual", binding));

        return toast.ToString(SaveOptions.DisableFormatting);
    }

    /// <summary>
    /// Programmatic self-verification of the anti-crop Toast XML invariants.
    /// Throws InvalidOperationException if any invariant is violated.
    /// </summary>
    public static void AssertQrToastXmlInvariants(string xml)
    {
        if (string.IsNullOrWhiteSpace(xml))
        {
            throw new InvalidOperationException("Toast XML is empty");
        }
        if (xml.Contains("placement=\"hero\"", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("Forbidden placement=\"hero\" detected in QR Toast XML!");
        }

        var doc = XDocument.Parse(xml);
        var toast = doc.Root ?? throw new InvalidOperationException("Missing <toast> root");
        if (!string.Equals(toast.Name.LocalName, "toast", StringComparison.Ordinal))
        {
            throw new InvalidOperationException("Root element must be <toast>");
        }
        var binding = toast.Element("visual")?.Element("binding")
            ?? throw new InvalidOperationException("Missing <visual><binding>");
        if (!string.Equals(binding.Attribute("template")?.Value, "ToastGeneric", StringComparison.Ordinal))
        {
            throw new InvalidOperationException("Binding template must be ToastGeneric");
        }

        var images = binding.Elements("image").ToList();
        if (images.Count != 1)
        {
            throw new InvalidOperationException($"Expected exactly 1 inline <image>, found {images.Count}");
        }
        var img = images[0];
        if (img.Attribute("placement") != null)
        {
            throw new InvalidOperationException("QR <image> must NOT specify a placement attribute (must remain inline)");
        }
        if (!string.Equals(img.Attribute("hint-crop")?.Value, "none", StringComparison.Ordinal))
        {
            throw new InvalidOperationException("QR <image> must set hint-crop=\"none\"");
        }
        var src = img.Attribute("src")?.Value ?? "";
        if (!src.StartsWith("file:///", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException($"QR <image> src must be file:/// URI, got: {src}");
        }

        var actions = toast.Element("actions")?.Elements("action").ToList() ?? new List<XElement>();
        if (!actions.Any(a => (a.Attribute("arguments")?.Value ?? "").StartsWith("action=open_png", StringComparison.Ordinal)))
        {
            throw new InvalidOperationException("Missing '放大查看' open_png action button");
        }
        if (!actions.Any(a => string.Equals(a.Attribute("arguments")?.Value, "action=cancel_pair", StringComparison.Ordinal)))
        {
            throw new InvalidOperationException("Missing '取消' cancel_pair action button");
        }
    }
}
