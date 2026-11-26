package top.weixiansen574.hybridfilexfer.core.protocol;

import java.util.Locale;

public enum RailType {
    ADB((byte) 0),
    USB_NIC((byte) 1),
    WIFI((byte) 2),
    ETH((byte) 3),
    OTHER((byte) 4);

    public final byte code;

    RailType(byte code) {
        this.code = code;
    }

    public static RailType fromCode(byte code) {
        for (RailType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return OTHER;
    }

    public static RailType inferFromInterfaceName(String name) {
        if (name == null || name.isEmpty()) {
            return OTHER;
        }
        if (ProtocolConstants.USB_ADB_RAIL_NAME.equalsIgnoreCase(name)) {
            return ADB;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ncm") || lower.startsWith("rndis") || lower.startsWith("usb")) {
            return USB_NIC;
        }
        if (lower.startsWith("wlan") || lower.startsWith("swlan") || lower.startsWith("ap")
                || lower.startsWith("wifi") || lower.startsWith("wi-fi") || lower.startsWith("p2p")) {
            return WIFI;
        }
        if (lower.startsWith("eth") || lower.startsWith("en")) {
            return ETH;
        }
        return OTHER;
    }
}
