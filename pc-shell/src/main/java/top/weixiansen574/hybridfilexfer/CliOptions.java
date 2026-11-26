package top.weixiansen574.hybridfilexfer;

import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;

/**
 * Parsed CLI options for the thin backwards-compatible PC shell.
 */
public final class CliOptions {
    public String connect;
    public String homeDir = "/";
    public String device;
    public String adbPath;
    public boolean adbLegacy;
    public Integer adbStreams;
    public boolean disableAutoBind;
    public boolean pairMode;
    public boolean autoMode;
    public boolean noToast;
    public boolean showHelp;
    public boolean showVersion;
    public boolean emptyArgs;

    public static final class ParseException extends Exception {
        public final String messageKey;
        public final String detail;

        public ParseException(String messageKey, String detail) {
            super(messageKey + ": " + detail);
            this.messageKey = messageKey;
            this.detail = detail;
        }
    }

    public static CliOptions parse(String[] args) throws ParseException {
        CliOptions opts = new CliOptions();
        if (args == null || args.length == 0) {
            opts.emptyArgs = true;
            return opts;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || !arg.startsWith("-")) {
                continue;
            }
            String key = arg;
            String value = null;
            boolean hasInlineValue = false;
            int eqPos = arg.indexOf('=');
            if (eqPos >= 0) {
                key = arg.substring(0, eqPos);
                if (eqPos < arg.length() - 1) {
                    value = arg.substring(eqPos + 1);
                    hasInlineValue = true;
                }
            }

            switch (key) {
                case "-h":
                case "--help":
                    opts.showHelp = true;
                    break;
                case "-v":
                case "--version":
                    opts.showVersion = true;
                    break;
                case "--adb-legacy":
                    opts.adbLegacy = !hasInlineValue || Boolean.parseBoolean(value);
                    break;
                case "--no-auto-bind":
                    opts.disableAutoBind = !hasInlineValue || Boolean.parseBoolean(value);
                    break;
                case "--pair":
                    opts.pairMode = !hasInlineValue || Boolean.parseBoolean(value);
                    break;
                case "--auto":
                    opts.autoMode = !hasInlineValue || Boolean.parseBoolean(value);
                    break;
                case "--no-toast":
                    opts.noToast = !hasInlineValue || Boolean.parseBoolean(value);
                    break;
                case "-c":
                case "--connect":
                    if (!hasInlineValue) {
                        value = consumeNextValue(args, i);
                        if (value != null) i++;
                    }
                    opts.connect = value;
                    break;
                case "-s":
                case "--switch-device":
                case "--device":
                    if (!hasInlineValue) {
                        value = consumeNextValue(args, i);
                        if (value != null) i++;
                    }
                    opts.device = value;
                    break;
                case "-d":
                case "--dir":
                    if (!hasInlineValue) {
                        value = consumeNextValue(args, i);
                        if (value != null) i++;
                    }
                    if (value != null && !value.isEmpty()) {
                        opts.homeDir = value;
                    }
                    break;
                case "--adb-path":
                    if (!hasInlineValue) {
                        value = consumeNextValue(args, i);
                        if (value != null) i++;
                    }
                    if (value == null || value.trim().isEmpty()) {
                        throw new ParseException("invalid_argument", key);
                    }
                    opts.adbPath = value.trim();
                    break;
                case "--adb-streams":
                    if (!hasInlineValue) {
                        value = consumeNextValue(args, i);
                        if (value != null) i++;
                    }
                    if (value == null || value.trim().isEmpty()) {
                        throw new ParseException("invalid_argument", key);
                    }
                    try {
                        int parsed = Integer.parseInt(value.trim());
                        if (parsed < 1 || parsed > ProtocolConstants.MAX_STREAMS_PER_RAIL) {
                            throw new ParseException("invalid_argument", key + "=" + value);
                        }
                        opts.adbStreams = parsed;
                    } catch (NumberFormatException e) {
                        throw new ParseException("invalid_argument", key + "=" + value);
                    }
                    break;
                default:
                    throw new ParseException("unknown_option", key);
            }
        }
        return opts;
    }

    private static String consumeNextValue(String[] args, int currentIndex) {
        if (currentIndex + 1 < args.length && !args[currentIndex + 1].startsWith("-")) {
            return args[currentIndex + 1];
        }
        return null;
    }
}
