package top.weixiansen574.hybridfilexfer.listadapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.NetCardIcon;
import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.engine.UsbNicAdvisor;

public class NetCardsAdapter extends RecyclerView.Adapter<NetCardsAdapter.ViewHolder> {

    private final Context context;
    private final UsbNicAdvisor advisor;
    private UsbNicAdvisor.AdviceResult lastAdvice;
    private List<ItemServerNetInterface> netInterfaceList;
    private boolean enableModify = true;

    public NetCardsAdapter(Context context) throws IOException {
        this(context, new UsbNicAdvisor());
    }

    public NetCardsAdapter(Context context, UsbNicAdvisor advisor) throws IOException {
        this.context = context;
        this.advisor = advisor != null ? advisor : new UsbNicAdvisor();
        this.netInterfaceList = loadAdvisedInterfaces();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_net_card_info, parent, false));
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final ItemServerNetInterface item = netInterfaceList.get(position);
        holder.txvNameAndState.setText(formatTitle(item));
        holder.txvIP.setText(formatSubtitle(item));

        boolean enableBindIpInput = item.railType != RailType.ADB;
        holder.imgInterfaceType.setImageDrawable(
                ContextCompat.getDrawable(context, NetCardIcon.matchIdForRailType(item.railType, item.name)));

        holder.editClientBindIP.setEnabled(enableModify && enableBindIpInput);
        holder.cbEnable.setEnabled(enableModify);

        // Detach previous listeners before updating state to prevent recycled/rebound ViewHolder contamination
        holder.cbEnable.setOnCheckedChangeListener(null);
        if (holder.bindIpWatcher != null) {
            holder.editClientBindIP.removeTextChangedListener(holder.bindIpWatcher);
            holder.bindIpWatcher = null;
        }

        holder.cbEnable.setChecked(item.enable);
        holder.editClientBindIP.setText(item.clientBindAddress);

        holder.cbEnable.setOnCheckedChangeListener((buttonView, isChecked) -> item.enable = isChecked);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                item.clientBindAddress = s != null ? s.toString() : "";
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        holder.bindIpWatcher = watcher;
        holder.editClientBindIP.addTextChangedListener(watcher);
    }

    private String formatTitle(ItemServerNetInterface item) {
        StringBuilder sb = new StringBuilder(item.name);
        if (!TextUtils.isEmpty(item.tag)) {
            sb.append(" [").append(item.tag).append("]");
        }
        sb.append(" | ").append(item.state);
        return sb.toString();
    }

    private String formatSubtitle(ItemServerNetInterface item) {
        String ip = item.address.getHostAddress();
        if (!TextUtils.isEmpty(item.note)) {
            return ip + " · " + item.note;
        }
        return ip;
    }

    @Override
    public int getItemCount() {
        return netInterfaceList.size();
    }

    public UsbNicAdvisor.AdviceResult getLastAdvice() {
        return lastAdvice;
    }

    public String getUdcSpeed() {
        return lastAdvice != null ? lastAdvice.udcSpeed : UsbNicAdvisor.SPEED_UNKNOWN;
    }

    public boolean isVpnActive() {
        return lastAdvice != null && lastAdvice.vpnActive;
    }

    public IpcPayloads.RailConflictResolvedPayload getConflictResolved() {
        return lastAdvice != null ? lastAdvice.conflictResolved : null;
    }

    public void changeItemState(String name, String state) {
        for (int i = 0; i < netInterfaceList.size(); i++) {
            ItemServerNetInterface item = netInterfaceList.get(i);
            if (item.name.equals(name)) {
                item.state = state;
                notifyItemChanged(i);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setEnableModify(boolean enableModify) {
        this.enableModify = enableModify;
        if (enableModify) {
            for (ItemServerNetInterface item : netInterfaceList) {
                item.state = context.getString(R.string.not_run);
            }
        }
        notifyDataSetChanged();
    }

    public List<ServerNetInterface> getSelectedInterfaces() {
        List<ServerNetInterface> serverNetInterfaceList = new ArrayList<>();
        for (ItemServerNetInterface netInterface : netInterfaceList) {
            if (netInterface.enable) {
                try {
                    serverNetInterfaceList.add(netInterface.toServerNetInterface());
                } catch (Exception e) {
                    Toast.makeText(context, context.getString(R.string.invalid_ip) + netInterface.clientBindAddress,
                            Toast.LENGTH_SHORT).show();
                    return null;
                }
            }
        }
        return serverNetInterfaceList;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void reload() throws SocketException, UnknownHostException {
        netInterfaceList = loadAdvisedInterfaces();
        notifyDataSetChanged();
    }

    private ArrayList<ItemServerNetInterface> loadAdvisedInterfaces() throws SocketException, UnknownHostException {
        UsbNicAdvisor.AdviceResult advice = advisor.advise();
        this.lastAdvice = advice;
        String defaultState = context.getString(R.string.not_run);
        String udcLabel = UsbNicAdvisor.formatUdcSpeedShortLabel(advice.udcSpeed);

        ArrayList<ItemServerNetInterface> items = new ArrayList<>(advice.nics.size());
        for (IpcDtos.NicInfo nic : advice.nics) {
            RailType railType = parseRailType(nic.railType, nic.name);
            InetAddress addr = InetAddress.getByName(nic.ip);
            ItemServerNetInterface item = new ItemServerNetInterface(nic.name, addr, railType, defaultState);
            item.enable = nic.selected;
            if (nic.clientBindIp != null) {
                item.clientBindAddress = nic.clientBindIp;
            }
            if (nic.usbNic) {
                item.tag = !udcLabel.isEmpty() ? udcLabel : "USB NIC";
            } else if (nic.vpn) {
                item.tag = "VPN";
            } else if (railType == RailType.ADB && !udcLabel.isEmpty()) {
                item.tag = udcLabel;
            }
            if (UsbNicAdvisor.REASON_USB_NIC_ACTIVE_AUTO_DISABLED.equals(nic.reason)
                    && advice.conflictResolved != null
                    && !advice.conflictResolved.activeUsbNics.isEmpty()) {
                item.note = "auto-off (" + TextUtils.join(",", advice.conflictResolved.activeUsbNics) + ")";
            }
            items.add(item);
        }
        return items;
    }

    private static RailType parseRailType(String railTypeName, String ifName) {
        if (railTypeName != null && !railTypeName.isEmpty()) {
            try {
                return RailType.valueOf(railTypeName);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (ProtocolConstants.USB_ADB_RAIL_NAME.equalsIgnoreCase(ifName)) {
            return RailType.ADB;
        }
        return RailType.inferFromInterfaceName(ifName);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox cbEnable;
        TextView txvNameAndState;
        TextView txvIP;
        EditText editClientBindIP;
        ImageView imgInterfaceType;
        TextWatcher bindIpWatcher;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cbEnable = itemView.findViewById(R.id.cb_interface_enable);
            txvNameAndState = itemView.findViewById(R.id.txv_interface_name_and_state);
            txvIP = itemView.findViewById(R.id.txv_interface_ip);
            editClientBindIP = itemView.findViewById(R.id.edit_client_bind_ip);
            imgInterfaceType = itemView.findViewById(R.id.img_interface_type);
        }
    }

    public static class ItemServerNetInterface {
        boolean enable = true;
        String name;
        InetAddress address;
        RailType railType;
        String clientBindAddress = "";
        String state;
        String tag = "";
        String note = "";

        public ItemServerNetInterface(String name, InetAddress address, RailType railType, String state) {
            this.name = name;
            this.address = address;
            this.railType = railType != null ? railType : RailType.inferFromInterfaceName(name);
            this.state = state;
        }

        public ServerNetInterface toServerNetInterface() throws UnknownHostException {
            InetAddress bindAddr = TextUtils.isEmpty(clientBindAddress)
                    ? null
                    : InetAddress.getByName(clientBindAddress.trim());
            return new ServerNetInterface(name, address, bindAddr, railType, 1);
        }
    }
}
