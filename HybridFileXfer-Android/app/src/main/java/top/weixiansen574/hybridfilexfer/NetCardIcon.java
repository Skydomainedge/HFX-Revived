package top.weixiansen574.hybridfilexfer;

import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

public class NetCardIcon {
    public static int matchIdForRailType(RailType railType, String iName) {
        if (railType != null) {
            switch (railType) {
                case ADB:
                case USB_NIC:
                    return R.drawable.usb;
                case WIFI:
                    return R.drawable.wifi;
                case ETH:
                    return R.drawable.ethernet;
                default:
                    break;
            }
        }
        return matchIdForIName(iName);
    }

    public static int matchIdForIName(String iName) {
        if (iName == null || iName.isEmpty()) {
            return R.drawable.ethernet;
        }
        if (ProtocolConstants.USB_ADB_RAIL_NAME.equalsIgnoreCase(iName)) {
            return R.drawable.usb;
        }
        String lower = iName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ncm") || lower.startsWith("usb") || lower.startsWith("rndis")) {
            return R.drawable.usb;
        } else if (lower.startsWith("wlan") || lower.startsWith("swlan") || lower.startsWith("ap")) {
            return R.drawable.wifi;
        } else if (lower.startsWith("bt")) {
            return R.drawable.bt;
        } else if (lower.startsWith("tun") || lower.startsWith("wg")) {
            return R.drawable.ethernet;
        } else {
            return R.drawable.ethernet;
        }
    }
}
