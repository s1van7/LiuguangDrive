package cn.liuguang.drive;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.List;

/**
 * 只读蓝牙状态。用公开 API 判断 A2DP（蓝牙音乐）是否真的连上，
 * 用来区分“已配对 ≠ 已连接”，避免界面误导用户。不读取 MAC，不做配对。
 */
final class BluetoothStatus {
    interface Listener { void onBluetoothChanged(String text, int connected, boolean enabled); }

    private final Context context;
    private final Listener listener;
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private boolean registered;
    private int connected = 0;
    private boolean enabled;
    private String text = "蓝牙状态未知";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { refresh(); }
    };

    BluetoothStatus(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
            if (!registered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
                filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                context.registerReceiver(receiver, filter);
                registered = true;
            }
            if (adapter != null && a2dp == null) {
                adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) { a2dp = proxy; refresh(); }
                    }
                    @Override public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) { a2dp = null; refresh(); }
                    }
                }, BluetoothProfile.A2DP);
            }
        } catch (Exception e) {
            text = "蓝牙接口不可用";
        }
        refresh();
    }

    void stop() {
        if (registered) {
            try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
            registered = false;
        }
        if (adapter != null && a2dp != null) {
            try { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp); } catch (Exception ignored) { }
        }
        a2dp = null;
    }

    void refresh() {
        String next;
        int count = 0;
        boolean on = false;
        try {
            on = adapter != null && adapter.isEnabled();
            if (adapter == null) next = "本机没有蓝牙模块";
            else if (!on) next = "蓝牙未开启";
            else {
                int bonded = 0;
                try {
                    java.util.Set<BluetoothDevice> devices = adapter.getBondedDevices();
                    bonded = devices == null ? 0 : devices.size();
                } catch (Exception ignored) { }
                if (a2dp != null) {
                    List<BluetoothDevice> list = a2dp.getConnectedDevices();
                    count = list == null ? 0 : list.size();
                }
                if (count > 0) next = "蓝牙音乐已连接";
                else if (a2dp == null) next = "蓝牙已开启 · 状态读取中";
                else next = bonded > 0 ? "已配对 " + bonded + " 台 · 未连接" : "蓝牙已开启 · 未连接";
            }
        } catch (Exception e) {
            next = "蓝牙状态读不到";
        }
        text = next;
        connected = count;
        enabled = on;
        if (listener != null) listener.onBluetoothChanged(text, connected, enabled);
    }

    String text() { return text; }
    int connectedCount() { return connected; }
    boolean enabled() { return enabled; }
}
