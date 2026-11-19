package cn.minis.devicecheck;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 仅采集系统公开的静态能力与即时快照，不评估真伪或硬件性能。 */
public final class DeviceInspector {
    private DeviceInspector() { }

    public static final class Metric {
        public final String label, value;
        public Metric(String label, String value) {
            this.label = label == null ? "" : label;
            this.value = value == null ? "系统未提供" : value;
        }
    }

    public static final class Section {
        public final String id, title, subtitle;
        public final List<Metric> metrics;
        public Section(String id, String title, String subtitle, List<Metric> metrics) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.metrics = Collections.unmodifiableList(metrics == null
                    ? new ArrayList<Metric>() : new ArrayList<Metric>(metrics));
        }
    }

    private interface Read { Object get() throws Exception; }
    private interface Gather { void run(List<Metric> out) throws Exception; }

    private static String text(Object obj) {
        if (obj == null) return "系统未提供";
        String s = String.valueOf(obj).trim();
        return s.length() == 0 ? "系统未提供" : s;
    }

    private static String failure(Throwable e) {
        return "系统限制/不支持（" + e.getClass().getSimpleName() + "）";
    }

    private static void item(List<Metric> out, String label, Read read) {
        try { out.add(new Metric(label, text(read.get()))); }
        catch (Exception | LinkageError e) { out.add(new Metric(label, failure(e))); }
    }

    private static void note(List<Metric> out, String label, String value) {
        out.add(new Metric(label, value));
    }

    private static void section(List<Section> out, String id, String title,
                                String subtitle, Gather gather) {
        List<Metric> values = new ArrayList<Metric>();
        try { gather.run(values); }
        catch (Exception | LinkageError e) { note(values, "采集限制", failure(e)); }
        if (values.isEmpty()) note(values, "采集结果", "系统未提供/不支持");
        out.add(new Section(id, title, subtitle, values));
    }

    public static List<Section> collect(Context context) {
        final Context c = context;
        List<Section> out = new ArrayList<Section>();
        section(out, "device", "设备", "厂商声明的设备信息，不构成真伪鉴定", v -> device(c, v));
        section(out, "system", "系统", "Android 系统、补丁及启动后运行时长", v -> system(v));
        section(out, "cpu", "处理器", "系统报告及有限 /proc、/sys 读取；无跑分或型号推断", v -> cpu(v));
        section(out, "memory", "内存", "系统即时快照；应用堆限制不等于物理内存", v -> memory(c, v));
        section(out, "storage", "存储", "应用内部数据分区统计，不是厂商标称容量", v -> storage(c, v));
        section(out, "battery", "电池", "广播与 BatteryManager 快照，不推算额定容量或损耗", v -> battery(c, v));
        section(out, "display", "屏幕", "显示模式像素与窗口可用尺寸分别列示", v -> display(c, v));
        section(out, "sensors", "传感器", "系统可枚举能力列表，不注册实时监听器", v -> sensors(c, v));
        section(out, "network", "网络", "当前活动网络快照；无探测请求，不读取 MAC", v -> network(c, v));
        section(out, "features", "硬件特性", "系统声明支持不代表已开启、已授权或当前可用", v -> features(c, v));
        section(out, "camera", "相机", "仅读取系统公开静态特征，不打开相机或拍照", v -> camera(c, v));
        return Collections.unmodifiableList(out);
    }

    public static String report(Context context, List<Section> sections) {
        StringBuilder b = new StringBuilder("Android 手机参数检查报告\n");
        try {
            b.append("导出时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.CHINA)
                    .format(new Date())).append('\n');
        } catch (Exception e) { b.append("导出时间：系统限制/不支持\n"); }
        b.append("说明：以下为系统报告的采集快照，非硬件鉴真、标称规格核验或性能评分。\n");
        b.append("隐私提示：报告可能包含设备名、型号、系统构建信息、相机 ID、IP 和 DNS 地址，分享前请检查。\n");
        b.append("不读取 IMEI、设备序列号、MAC 或 root 状态；不采集定位、不联网、不打开相机。\n");
        b.append("各分区采集时间不完全一致；失败项仅表示系统限制/不支持，不代表硬件不存在。\n");
        if (sections == null) b.append("\n未提供采集结果。\n");
        else for (Section s : sections) {
            if (s == null) continue;
            b.append('\n').append('【').append(s.title).append("】\n");
            if (s.subtitle != null) b.append(s.subtitle).append('\n');
            for (Metric m : s.metrics) {
                if (m != null) b.append(m.label).append("：").append(m.value).append('\n');
            }
        }
        return b.toString();
    }

    private static void device(Context c, List<Metric> v) {
        item(v, "设备名称（系统设置）", () -> Settings.Global.getString(c.getContentResolver(), "device_name"));
        item(v, "制造商", () -> Build.MANUFACTURER);
        item(v, "品牌", () -> Build.BRAND);
        item(v, "型号", () -> Build.MODEL);
        item(v, "产品", () -> Build.PRODUCT);
        item(v, "设备代号", () -> Build.DEVICE);
        item(v, "主板代号", () -> Build.BOARD);
        item(v, "硬件代号", () -> Build.HARDWARE);
        item(v, "支持 ABI（有序）", () -> Arrays.toString(Build.SUPPORTED_ABIS));
        item(v, "32 位 ABI", () -> Arrays.toString(Build.SUPPORTED_32_BIT_ABIS));
        item(v, "64 位 ABI", () -> Arrays.toString(Build.SUPPORTED_64_BIT_ABIS));
        item(v, "当前应用进程", () -> android.os.Process.is64Bit() ? "64 位" : "32 位");
    }

    private static void system(List<Metric> v) {
        item(v, "Android 版本", () -> Build.VERSION.RELEASE);
        item(v, "API / SDK", () -> Build.VERSION.SDK_INT);
        item(v, "安全补丁（厂商声明）", () -> Build.VERSION.SECURITY_PATCH);
        item(v, "构建版本", () -> Build.DISPLAY);
        item(v, "构建 ID", () -> Build.ID);
        item(v, "构建类型", () -> Build.TYPE);
        item(v, "系统构建指纹（非个人指纹）", () -> Build.FINGERPRINT);
        item(v, "内核版本", () -> System.getProperty("os.version"));
        item(v, "运行时架构（非 SoC 型号）", () -> System.getProperty("os.arch"));
        item(v, "Java VM", () -> text(System.getProperty("java.vm.name")) + " / "
                + text(System.getProperty("java.vm.version")));
        item(v, "启动后时长（含休眠）", () -> duration(SystemClock.elapsedRealtime()));
        item(v, "启动后清醒时长（不含深度休眠）", () -> duration(SystemClock.uptimeMillis()));
    }

    private static String duration(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.CHINA, "%d 天 %02d:%02d:%02d", s / 86400L,
                (s / 3600L) % 24L, (s / 60L) % 60L, s % 60L);
    }

    private static void cpu(List<Metric> v) {
        item(v, "运行时可用逻辑处理器数", () -> Runtime.getRuntime().availableProcessors());
        item(v, "在线 CPU 编号（内核报告）", () -> readSmall("/sys/devices/system/cpu/online", 1024));
        item(v, "可能 CPU 编号（非当前在线数量）", () -> readSmall("/sys/devices/system/cpu/possible", 1024));
        if (Build.VERSION.SDK_INT >= 31) {
            item(v, "SoC 型号（系统声明）", () -> Build.SOC_MODEL);
            item(v, "SoC 厂商（系统声明）", () -> Build.SOC_MANUFACTURER);
        } else note(v, "SoC 型号", "系统限制/不支持：Android 12 / API 31 以下无 Build.SOC_MODEL");
        item(v, "处理器名称（/proc/cpuinfo 尽力读取）", DeviceInspector::cpuName);
        item(v, "硬件代号回退（不是处理器型号）", () -> Build.HARDWARE);
        item(v, "CPU0 当前频率", () -> cpuFrequency("scaling_cur_freq"));
        item(v, "CPU0 硬件最低频率", () -> cpuFrequency("cpuinfo_min_freq"));
        item(v, "CPU0 硬件最高频率", () -> cpuFrequency("cpuinfo_max_freq"));
        note(v, "读取范围", "仅固定 /proc/cpuinfo、CPU 编号文件及 CPU0/policy0 频率文件；不遍历目录。频率随调度变化，不代表所有核心或实际跑分。");
    }

    private static String readSmall(String path, int maxChars) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), "UTF-8"))) {
            char[] buf = new char[Math.min(maxChars + 1, 4096)];
            StringBuilder b = new StringBuilder();
            while (b.length() <= maxChars) {
                int n = r.read(buf, 0, Math.min(buf.length, maxChars + 1 - b.length()));
                if (n < 0) break;
                b.append(buf, 0, n);
            }
            boolean cut = b.length() > maxChars;
            String result = b.substring(0, Math.min(b.length(), maxChars)).trim();
            return result.isEmpty() ? "系统未提供（文件为空）" : result + (cut ? " [已截断]" : "");
        }
    }

    private static String cpuName() throws Exception {
        // 仅保留名称白名单，绝不输出 cpuinfo 中可能存在的 Serial 字段。
        String raw = readSmall("/proc/cpuinfo", 65536);
        List<String> names = new ArrayList<String>();
        for (String line : raw.split("\n")) {
            int p = line.indexOf(':');
            if (p < 0) continue;
            String key = line.substring(0, p).trim().toLowerCase(Locale.US);
            if (!(key.equals("hardware") || key.equals("model name")
                    || key.equals("processor") || key.equals("cpu model")
                    || key.equals("system type"))) continue;
            String val = line.substring(p + 1).trim();
            if (val.isEmpty() || val.matches("[0-9]+")) continue;
            String entry = key + ": " + val;
            if (!names.contains(entry) && names.size() < 16) names.add(entry);
        }
        return names.isEmpty() ? "系统限制/未提供可识别的名称；未将 ABI 或硬件代号当作型号" : join(names);
    }

    private static String cpuFrequency(String file) {
        String[] bases = {"/sys/devices/system/cpu/cpu0/cpufreq/",
                "/sys/devices/system/cpu/cpufreq/policy0/"};
        List<String> errors = new ArrayList<String>();
        for (String base : bases) {
            try {
                long khz = Long.parseLong(readSmall(base + file, 128));
                if (khz <= 0) { errors.add(base + file + "：系统未提供有效正值"); continue; }
                return khz + " kHz (" + decimal(khz / 1000.0) + " MHz)，来源 " + base + file;
            } catch (Exception | LinkageError e) { errors.add(base + file + "：" + failure(e)); }
        }
        return join(errors);
    }

    private static <T> T service(Context c, Class<T> type) {
        T s = c.getSystemService(type);
        if (s == null) throw new UnsupportedOperationException("service unavailable");
        return s;
    }

    private static String decimal(double n) {
        return String.format(Locale.CHINA, "%.2f", n);
    }

    private static String bytes(long n) {
        if (n < 0) return "系统未提供有效字节值";
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        double x = n;
        int i = 0;
        while (x >= 1024 && i < units.length - 1) { x /= 1024; i++; }
        return decimal(x) + " " + units[i] + " (" + n + " B)";
    }

    private static String join(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String val : values) { if (b.length() > 0) b.append("；"); b.append(val); }
        return b.length() == 0 ? "无（系统报告的空列表）" : b.toString();
    }

    private static void memory(Context c, List<Metric> v) {
        try {
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            service(c, ActivityManager.class).getMemoryInfo(m);
            item(v, "系统总内存（可供内核使用）", () -> bytes(m.totalMem));
            item(v, "系统可用内存", () -> bytes(m.availMem));
            item(v, "低内存阈值", () -> bytes(m.threshold));
            item(v, "系统低内存状态", () -> m.lowMemory ? "是" : "否");
        } catch (Exception | LinkageError e) { note(v, "系统内存快照", failure(e)); }
        item(v, "应用普通内存等级", () -> service(c, ActivityManager.class).getMemoryClass() + " MiB");
        item(v, "应用 largeHeap 内存等级", () -> service(c, ActivityManager.class).getLargeMemoryClass() + " MiB（仅限声明 largeHeap 的应用，不代表本进程上限）");
        item(v, "本进程 Java 堆上限", () -> bytes(Runtime.getRuntime().maxMemory()));
        item(v, "本进程当前 Java 堆已分配", () -> bytes(Runtime.getRuntime().totalMemory()));
        item(v, "本进程当前 Java 堆内空闲", () -> bytes(Runtime.getRuntime().freeMemory()));
    }

    private static void storage(Context c, List<Metric> v) {
        item(v, "统计路径", () -> c.getFilesDir().getAbsolutePath());
        try {
            File dir = c.getFilesDir();
            StatFs fs = new StatFs(dir.getAbsolutePath());
            item(v, "内部数据分区总空间", () -> bytes(fs.getTotalBytes()));
            item(v, "本应用可用空间", () -> bytes(fs.getAvailableBytes()));
            item(v, "分区空闲空间（含保留块）", () -> bytes(fs.getFreeBytes()));
            item(v, "文件系统块大小", () -> bytes(fs.getBlockSizeLong()));
        } catch (Exception | LinkageError e) { note(v, "分区统计", failure(e)); }
        note(v, "容量口径", "StatFs 仅统计应用内部文件所在文件系统；系统/恢复分区、保留空间和十进制容量口径不同，不能据此认定整机标称闪存容量或扩容真伪。");
    }

    private static int extra(Intent i, String key) {
        if (!i.hasExtra(key)) throw new UnsupportedOperationException("missing extra");
        int n = i.getIntExtra(key, Integer.MIN_VALUE);
        if (n == Integer.MIN_VALUE || n == Integer.MAX_VALUE)
            throw new UnsupportedOperationException("invalid sentinel");
        return n;
    }

    private static void battery(Context c, List<Metric> v) {
        try {
            final Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) note(v, "电池广播", "系统限制/不支持：未返回电池粘性广播");
            else {
                item(v, "电量", () -> {
                    int level = extra(i, BatteryManager.EXTRA_LEVEL);
                    int scale = extra(i, BatteryManager.EXTRA_SCALE);
                    if (scale <= 0 || level < 0 || level > scale) return "系统未提供有效电量/量程";
                    return decimal(level * 100.0 / scale) + "%（广播原始值 " + level + "/" + scale + "）";
                });
                item(v, "充电状态", () -> batteryStatus(extra(i, BatteryManager.EXTRA_STATUS)));
                item(v, "健康状态（非容量健康度）", () -> batteryHealth(extra(i, BatteryManager.EXTRA_HEALTH)));
                item(v, "外部电源类型", () -> plugged(extra(i, BatteryManager.EXTRA_PLUGGED)));
                item(v, "是否存在电池", () -> {
                    if (!i.hasExtra(BatteryManager.EXTRA_PRESENT)) return "系统未提供";
                    return i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) ? "是" : "否";
                });
                item(v, "电池温度", () -> decimal(extra(i, BatteryManager.EXTRA_TEMPERATURE) / 10.0) + " °C（广播值）");
                item(v, "电池电压", () -> {
                    int mv = extra(i, BatteryManager.EXTRA_VOLTAGE);
                    return mv > 0 ? mv + " mV" : "系统未提供有效正电压";
                });
                item(v, "电池技术", () -> i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
                if (Build.VERSION.SDK_INT >= 34) item(v, "循环次数（系统报告）", () -> {
                    int n = extra(i, BatteryManager.EXTRA_CYCLE_COUNT);
                    return n >= 0 ? n + " 次（0 也可能为厂商未填充）" : "系统未提供有效值";
                });
            }
        } catch (Exception | LinkageError e) { note(v, "电池广播", failure(e)); }
        try {
            final BatteryManager bm = service(c, BatteryManager.class);
            item(v, "是否正在充电（BatteryManager）", () -> bm.isCharging() ? "是" : "否");
            item(v, "电量属性", () -> batteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CAPACITY, 0));
            item(v, "瞬时电流", () -> batteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 1));
            item(v, "平均电流", () -> batteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, 1));
            item(v, "剩余电荷（非额定容量）", () -> batteryProperty(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 2));
            item(v, "剩余能量", () -> {
                long n = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
                return n < 0 || n == Long.MAX_VALUE ? "系统限制/不支持：未返回有效属性值"
                        : n + " nWh（0 无法区分真实零值与厂商占位）";
            });
        } catch (Exception | LinkageError e) { note(v, "BatteryManager 属性", failure(e)); }
        note(v, "属性支持与口径", "逐项查询；MIN/MAX 哨兵值及不合法范围标记为不支持，不将剩余电荷当作设计/额定容量。电流按 Android 定义正值充入、负值放出；0 不能单凭此值确认支持与否，厂商实现可能有差异。不计算电池损耗。");
    }

    private static String batteryProperty(BatteryManager bm, int property, int kind) {
        int n = bm.getIntProperty(property);
        if (n == Integer.MIN_VALUE || n == Integer.MAX_VALUE || (kind != 1 && n < 0)
                || (kind == 0 && n > 100)) return "系统限制/不支持：未返回有效属性值";
        String unit = kind == 0 ? "%" : kind == 1 ? " µA" : " µAh";
        return n + unit + (n == 0 ? "（0 无法区分真实零值与厂商占位）" : "");
    }

    private static String batteryStatus(int n) {
        switch (n) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "正在充电";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "正在放电";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "未充电";
            case BatteryManager.BATTERY_STATUS_FULL: return "已充满";
            case BatteryManager.BATTERY_STATUS_UNKNOWN: return "系统报告未知";
            default: return "未知状态（原始值 " + n + "）";
        }
    }

    private static String batteryHealth(int n) {
        switch (n) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "系统报告良好（不是损耗百分比）";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "过热";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "失效";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "电压过高";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "未指定故障";
            case BatteryManager.BATTERY_HEALTH_COLD: return "温度过低";
            case BatteryManager.BATTERY_HEALTH_UNKNOWN: return "系统报告未知";
            default: return "未知状态（原始值 " + n + "）";
        }
    }

    private static String plugged(int mask) {
        if (mask == 0) return "未接外部电源";
        List<String> sources = new ArrayList<String>();
        if ((mask & BatteryManager.BATTERY_PLUGGED_AC) != 0) sources.add("交流电源");
        if ((mask & BatteryManager.BATTERY_PLUGGED_USB) != 0) sources.add("USB");
        if ((mask & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) sources.add("无线充电");
        // BATTERY_PLUGGED_DOCK 的值为 8；使用数值保留老版本兼容性。
        if ((mask & 8) != 0) sources.add("底座");
        return join(sources) + "（原始位掩码 " + mask + "）";
    }

    @SuppressWarnings("deprecation")
    private static void display(Context c, List<Metric> v) {
        WindowManager wm = service(c, WindowManager.class);
        Display d = wm.getDefaultDisplay();
        if (d == null) throw new UnsupportedOperationException("no default display");
        item(v, "显示名称", d::getName);
        item(v, "当前显示模式像素", () -> {
            Display.Mode m = d.getMode();
            return m.getPhysicalWidth() + " × " + m.getPhysicalHeight()
                    + " px（当前模式，不保证等于面板原生像素）";
        });
        item(v, "当前模式刷新率", () -> decimal(d.getMode().getRefreshRate()) + " Hz");
        item(v, "当前显示刷新率", () -> decimal(d.getRefreshRate()) + " Hz（系统报告）");
        item(v, "可切换显示模式", () -> {
            List<String> modes = new ArrayList<String>();
            for (Display.Mode m : d.getSupportedModes()) {
                modes.add("ID " + m.getModeId() + "：" + m.getPhysicalWidth() + " × "
                        + m.getPhysicalHeight() + " px @ " + decimal(m.getRefreshRate()) + " Hz");
            }
            return join(modes);
        });
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        item(v, "资源显示区域（逻辑像素）", () -> dm.widthPixels + " × " + dm.heightPixels + " px");
        if (Build.VERSION.SDK_INT >= 30) {
            item(v, "当前窗口边界", () -> {
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                return bounds.width() + " × " + bounds.height()
                        + " px（含系统栏覆盖区域，非扣除遮挡后的内容区）";
            });
        } else {
            item(v, "应用可用显示区域（旧版 API）", () -> {
                android.graphics.Point size = new android.graphics.Point();
                d.getSize(size);
                return size.x + " × " + size.y + " px（可能受系统栏、多窗口影响）";
            });
        }
        item(v, "逻辑密度 DPI", () -> dm.densityDpi + " dpi（逻辑值，受显示缩放设置影响）");
        item(v, "逻辑密度倍率", () -> decimal(dm.density));
        item(v, "系统报告的水平 / 垂直 DPI", () -> decimal(dm.xdpi) + " / "
                + decimal(dm.ydpi) + " dpi（厂商可近似填写，不据此计算屏幕英寸）");
        item(v, "系统字体缩放", () -> decimal(c.getResources().getConfiguration().fontScale));
        item(v, "宽色域支持", () -> d.isWideColorGamut() ? "系统报告支持" : "系统未声明支持");
        item(v, "HDR 类型", () -> {
            Display.HdrCapabilities hdr = d.getHdrCapabilities();
            return hdr == null ? "系统未提供" : Arrays.toString(hdr.getSupportedHdrTypes())
                    + "（1=Dolby Vision，2=HDR10，3=HLG，4=HDR10+；非亮度实测）";
        });
        item(v, "亮度模式（系统设置）", () -> {
            int mode = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) return "自动";
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return "手动";
            return "系统未识别值：" + mode;
        });
        item(v, "亮度设置原始值", () -> Settings.System.getInt(c.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS) + "（旧版设置项，不等于当前实际亮度或尼特值）");
        note(v, "尺寸说明", "显示模式、资源显示区域和窗口边界口径不同；不将逻辑尺寸或系统上报 DPI 当作面板物理尺寸。未测试触摸、坏点或亮度。" );
    }

    private static void sensors(Context c, List<Metric> v) {
        List<Sensor> list = service(c, SensorManager.class).getSensorList(Sensor.TYPE_ALL);
        note(v, "系统公开传感器数量", list.size() + "（可能包含虚拟/组合传感器；不等于实体元件数量）");
        for (int i = 0; i < list.size(); i++) {
            final Sensor s = list.get(i);
            item(v, (i + 1) + ". " + s.getName(), () -> "厂商：" + text(s.getVendor())
                    + "\n类型：" + text(s.getStringType()) + " / " + s.getType()
                    + "\n版本：" + s.getVersion() + "；唤醒型：" + (s.isWakeUpSensor() ? "是" : "否")
                    + "\n最大量程：" + s.getMaximumRange() + "；分辨率：" + s.getResolution()
                    + "（单位依传感器类型）\n标称功耗：" + s.getPower() + " mA；最小采样间隔："
                    + s.getMinDelay() + " µs");
        }
        note(v, "检测边界", "仅列举系统公开能力与标称参数；不注册实时监听、不采集身体健康数据，不能据此判断传感器工作是否正常。" );
    }

    private static void network(Context c, List<Metric> v) {
        ConnectivityManager cm = service(c, ConnectivityManager.class);
        Network active = cm.getActiveNetwork();
        if (active == null) {
            note(v, "默认网络", "无活动默认网络或系统未向本应用提供");
        } else {
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null) note(v, "网络能力", "系统未提供（网络可能刚发生变化）");
            else {
                item(v, "传输类型", () -> {
                    List<String> types = new ArrayList<String>();
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("Wi-Fi");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("蜂窝网络");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types.add("以太网");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) types.add("蓝牙");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types.add("VPN");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) types.add("Wi-Fi Aware");
                    if (types.isEmpty()) types.add("其他系统传输类型");
                    return join(types);
                });
                item(v, "系统声明可访问互联网", () -> caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET) ? "是（不代表实测连通）" : "否");
                item(v, "系统互联网连通验证", () -> caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "系统已验证" : "尚未验证或验证未通过");
                item(v, "门户认证网络", () -> caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ? "系统检测到门户认证" : "系统未声明");
                item(v, "当前网络是否计量", () -> cm.isActiveNetworkMetered() ? "是" : "否");
            }
            LinkProperties link = cm.getLinkProperties(active);
            if (link == null) note(v, "链路信息", "系统未提供（网络可能刚发生变化）");
            else {
                item(v, "网络接口", link::getInterfaceName);
                item(v, "本地 IP / 前缀长度", () -> {
                    List<String> ips = new ArrayList<String>();
                    for (LinkAddress address : link.getLinkAddresses()) {
                        ips.add(address.getAddress().getHostAddress() + "/" + address.getPrefixLength());
                    }
                    return join(ips);
                });
                item(v, "DNS 服务器", () -> {
                    List<String> dns = new ArrayList<String>();
                    for (InetAddress address : link.getDnsServers()) dns.add(address.getHostAddress());
                    return join(dns);
                });
                if (Build.VERSION.SDK_INT >= 29) {
                    item(v, "MTU", () -> {
                        int mtu = link.getMtu();
                        return mtu > 0 ? mtu + " B" : "系统未提供有效值";
                    });
                } else {
                    note(v, "MTU", "系统限制：Android 10 / API 29 以下无公开查询接口");
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    item(v, "私有 DNS 是否激活", () -> link.isPrivateDnsActive() ? "是" : "否");
                    item(v, "私有 DNS 名称", link::getPrivateDnsServerName);
                }
            }
        }
        note(v, "Wi-Fi SSID / BSSID / MAC", "未采集：不申请定位/附近设备权限，不读取设备 MAC");
        note(v, "蜂窝网络详情", "未采集：不申请电话/定位权限，不读取号码、SIM 标识或基站信息");
        note(v, "连接说明", "仅显示本应用可见默认网络；VPN、工作资料或网络切换可能改变结果。无 INTERNET 权限，不测速、不探测外网 IP、不扫描附近网络。" );
    }

    private static void features(Context c, List<Metric> v) {
        PackageManager pm = c.getPackageManager();
        String[][] features = {
                {"NFC", "android.hardware.nfc"},
                {"蓝牙", "android.hardware.bluetooth"},
                {"蓝牙低功耗", "android.hardware.bluetooth_le"},
                {"Wi-Fi", "android.hardware.wifi"},
                {"Wi-Fi Direct", "android.hardware.wifi.direct"},
                {"GPS", "android.hardware.location.gps"},
                {"电话能力", "android.hardware.telephony"},
                {"指纹识别", "android.hardware.fingerprint"},
                {"人脸识别", "android.hardware.biometrics.face"},
                {"USB Host", "android.hardware.usb.host"},
                {"USB Accessory", "android.hardware.usb.accessory"},
                {"任意相机", "android.hardware.camera.any"},
                {"前置相机", "android.hardware.camera.front"},
                {"闪光灯", "android.hardware.camera.flash"},
                {"麦克风", "android.hardware.microphone"},
                {"加速度计", "android.hardware.sensor.accelerometer"},
                {"陀螺仪", "android.hardware.sensor.gyroscope"},
                {"气压计", "android.hardware.sensor.barometer"},
                {"计步器", "android.hardware.sensor.stepcounter"},
                {"触摸屏", "android.hardware.touchscreen"}
        };
        for (String[] feature : features) {
            item(v, feature[0], () -> pm.hasSystemFeature(feature[1]) ? "系统声明支持" : "系统未声明支持");
        }
        item(v, "OpenGL ES（系统声明）", () -> {
            android.content.pm.ConfigurationInfo info = service(c, ActivityManager.class).getDeviceConfigurationInfo();
            return info == null ? "系统未提供" : info.getGlEsVersion();
        });
        note(v, "能力说明", "这里只读取 PackageManager 的硬件特性声明；不代表已开启、已录入生物信息、已获权限，亦非实测硬件功能。" );
    }

    @android.annotation.SuppressLint("MissingPermission") // 仅读取静态元数据；缺少权限时逐项降级，不打开相机。
    private static void camera(Context c, List<Metric> v) {
        CameraManager manager = service(c, CameraManager.class);
        String[] ids;
        try { ids = manager.getCameraIdList(); }
        catch (Exception | LinkageError e) {
            note(v, "相机枚举", failure(e));
            return;
        }
        note(v, "系统向本应用公开的相机数量", ids.length + "（不等于实体镜头总数；厂商可能隐藏部分相机）");
        for (String id : ids) {
            final CameraCharacteristics camera;
            try { camera = manager.getCameraCharacteristics(id); }
            catch (Exception | LinkageError e) {
                note(v, "相机 ID " + id, failure(e));
                continue;
            }
            String prefix = "相机 " + id + " / ";
            item(v, prefix + "方向", () -> {
                Integer facing = camera.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) return "系统未提供";
                if (facing == CameraCharacteristics.LENS_FACING_BACK) return "后置";
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "前置";
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) return "外置";
                return "未知方向（" + facing + "）";
            });
            item(v, prefix + "像素阵列", () -> {
                Size size = camera.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                if (size == null) return "系统限制/未提供";
                return size.getWidth() + " × " + size.getHeight() + " px，"
                        + decimal(size.getWidth() * (double) size.getHeight() / 1000000.0)
                        + " MP（非当前照片输出分辨率，不代表画质）";
            });
            item(v, prefix + "有效像素区域", () -> {
                Rect area = camera.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                return area == null ? "系统限制/未提供" : area.width() + " × " + area.height() + " px";
            });
            item(v, prefix + "系统公开的最大 JPEG 尺寸", () -> {
                StreamConfigurationMap map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                if (sizes == null || sizes.length == 0) return "系统限制/未提供";
                Size largest = sizes[0];
                for (Size size : sizes) {
                    if ((long) size.getWidth() * size.getHeight() > (long) largest.getWidth() * largest.getHeight()) largest = size;
                }
                return largest.getWidth() + " × " + largest.getHeight()
                        + " px（常规配置表，不保证涵盖超高分辨率/厂商模式）";
            });
            item(v, prefix + "可用焦距", () -> {
                float[] values = camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                return values == null ? "系统限制/未提供" : Arrays.toString(values) + " mm（非 35 mm 等效焦距）";
            });
            item(v, prefix + "可用光圈", () -> {
                float[] values = camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                return values == null ? "系统限制/未提供" : "f/ " + Arrays.toString(values);
            });
            item(v, prefix + "闪光灯", () -> {
                Boolean flash = camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                return flash == null ? "系统未提供" : flash ? "系统报告支持" : "系统未声明支持";
            });
            item(v, prefix + "传感器方向", () -> {
                Integer angle = camera.get(CameraCharacteristics.SENSOR_ORIENTATION);
                return angle == null ? "系统未提供" : angle + "°";
            });
            item(v, prefix + "Camera2 硬件等级", () -> {
                Integer level = camera.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level == null) return "系统未提供";
                switch (level) {
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
                    case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
                    default: return "未知等级（" + level + "）";
                }
            });
        }
        note(v, "权限与限制", "未申请 CAMERA 权限，不打开相机。部分静态元数据会因权限或厂商限制缺失；数量/像素仅为系统报告，不是镜头真伪鉴定或成像测试。" );
    }
}
