package top.weixiansen574.hybridfilexfer.core.bean;

import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;

import top.weixiansen574.hybridfilexfer.core.protocol.RailType;

public class ServerNetInterface {
    public final String name;
    public final InetAddress address;
    public final InetAddress clientBindAddress;
    public final RailType railType;
    public final int streamCount;

    public ServerNetInterface(String name, InetAddress address, InetAddress clientBindAddress) {
        this(name, address, clientBindAddress, RailType.inferFromInterfaceName(name), 1);
    }

    public ServerNetInterface(String name, InetAddress address, InetAddress clientBindAddress,
                              RailType railType, int streamCount) {
        this.name = name;
        this.address = address;
        this.clientBindAddress = clientBindAddress;
        this.railType = railType != null ? railType : RailType.inferFromInterfaceName(name);
        this.streamCount = Math.max(1, Math.min(8, streamCount));
    }

    @NotNull
    @Override
    public String toString() {
        return "ServerNetInterface{" +
                "name='" + name + '\'' +
                ", railType=" + railType +
                ", streamCount=" + streamCount +
                ", address=" + address +
                ", clientBindAddress=" + clientBindAddress +
                '}';
    }
}
