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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.NetCardIcon;
import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcDtos;
import top.weixiansen574.hybridfilexfer.core.ipc.IpcPayloads;
import top.weixiansen574.hybridfilexfer.core.protocol.ProtocolConstants;
import top.weixiansen574.hybridfilexfer.core.protocol.RailType;
import top.weixiansen574.hybridfilexfer.engine.UsbNicAdvisor;

/**
 * Pure thin display adapter rendering {@link IpcDtos.NicInfo} supplied by {@code IHfxEngine}.
 */
public class NetCardsAdapter extends RecyclerView.Adapter<NetCardsAdapter.ViewHolder> {

    private final Context context;
    private List<ItemServerNetInterface> netInterfaceList = new ArrayList<>();
    private boolean enableModify = true;

    public NetCardsAdapter(Context context) {
        this.context = context;
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
        if (!TextUtils.isEmpty(item.note)) {
            return item.ip + " · " + item.note;
        }
        return item.ip;
    }

    @Override
    public int getItemCount() {
        return netInterfaceList.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateFromNics(List<IpcDtos.NicInfo> nics,
                               String udcSpeed,
                               IpcPayloads.RailConflictResolvedPayload conflictResolved,
                               List<IpcDtos.RailStatusInfo> activeRails) {
        String defaultState = context.getString(R.string.not_run);
        String udcLabel = UsbNicAdvisor.formatUdcSpeedShortLabel(udcSpeed);

        List<ItemServerNetInterface> items = new ArrayList<>();
        if (nics != null) {
            for (IpcDtos.NicInfo nic : nics) {
                RailType railType = parseRailType(nic.railType, nic.name);
                ItemServerNetInterface item = new ItemServerNetInterface(nic.name, nic.ip, railType, defaultState);
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
                        && conflictResolved != null
                        && !conflictResolved.activeUsbNics.isEmpty()) {
                    item.note = "auto-off (" + TextUtils.join(",", conflictResolved.activeUsbNics) + ")";
                }
                items.add(item);
            }
        }
        this.netInterfaceList = items;
        if (activeRails != null && !activeRails.isEmpty()) {
            applyRailStatuses(activeRails);
        } else {
            notifyDataSetChanged();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void applyRailStatuses(List<IpcDtos.RailStatusInfo> rails) {
        if (rails == null || rails.isEmpty()) {
            String notRun = context.getString(R.string.not_run);
            for (ItemServerNetInterface item : netInterfaceList) {
                item.state = notRun;
            }
            notifyDataSetChanged();
            return;
        }
        for (ItemServerNetInterface item : netInterfaceList) {
            for (IpcDtos.RailStatusInfo r : rails) {
                if (item.name.equals(r.name)) {
                    item.state = mapRailStateLabel(r.state, r.streamCount);
                    break;
                }
            }
        }
        notifyDataSetChanged();
    }

    private String mapRailStateLabel(String railState, int streamCount) {
        if ("CONNECTED".equals(railState)) {
            String base = context.getString(R.string.yi_lian_jie);
            return streamCount > 1 ? (base + " ×" + streamCount) : base;
        }
        if ("WAITING".equals(railState) || "STARTING".equals(railState)) {
            return context.getString(R.string.deng_dai_lian_jie);
        }
        if ("FAILED".equals(railState)) {
            return context.getString(R.string.lian_jie_shi_bai);
        }
        return context.getString(R.string.not_run);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setEnableModify(boolean enableModify) {
        this.enableModify = enableModify;
        if (enableModify) {
            String notRun = context.getString(R.string.not_run);
            for (ItemServerNetInterface item : netInterfaceList) {
                item.state = notRun;
            }
        }
        notifyDataSetChanged();
    }

    public List<IpcDtos.RailSelection> getSelectedRailSelections() {
        List<IpcDtos.RailSelection> selections = new ArrayList<>();
        for (ItemServerNetInterface item : netInterfaceList) {
            if (!item.enable) {
                continue;
            }
            String bindTrimmed = item.clientBindAddress != null ? item.clientBindAddress.trim() : "";
            if (!bindTrimmed.isEmpty()) {
                try {
                    InetAddress.getByName(bindTrimmed);
                } catch (UnknownHostException e) {
                    Toast.makeText(context, context.getString(R.string.invalid_ip) + bindTrimmed,
                            Toast.LENGTH_SHORT).show();
                    return null;
                }
            }
            IpcDtos.RailSelection sel = new IpcDtos.RailSelection();
            sel.name = item.name;
            sel.ip = item.ip;
            sel.bindIp = bindTrimmed.isEmpty() ? null : bindTrimmed;
            sel.railType = item.railType.name();
            sel.streamCount = 1;
            selections.add(sel);
        }
        return selections;
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
        String ip;
        RailType railType;
        String clientBindAddress = "";
        String state;
        String tag = "";
        String note = "";

        public ItemServerNetInterface(String name, String ip, RailType railType, String state) {
            this.name = name;
            this.ip = ip != null ? ip : "127.0.0.1";
            this.railType = railType != null ? railType : RailType.inferFromInterfaceName(name);
            this.state = state;
        }
    }
}
