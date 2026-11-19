package cn.minis.devicecheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 纯原生界面，无网络、广告、分析 SDK 或第三方依赖。 */
public final class MainActivity extends Activity {
    private static final int BG = 0xFFF3F6F8;
    private static final int INK = 0xFF172D35;
    private static final int MUTED = 0xFF657781;
    private static final int TEAL = 0xFF007C72;
    private static final int PALE = 0xFFE0F2ED;
    private static final int EXPORT_REQUEST = 100;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<DeviceInspector.Section> sections = new ArrayList<>();
    private final Set<String> collapsed = new HashSet<>();
    private LinearLayout cards;
    private LinearLayout chipRow;
    private TextView status;
    private TextView hero;
    private TextView summary;
    private ProgressBar progress;
    private Button refreshButton;
    private Button exportButton;
    private Button shareButton;
    private String selected = "all";
    private String query = "";
    private String pendingReport;
    private long capturedAt;
    private long collectionId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            selected = state.getString("selected", "all");
            query = state.getString("query", "");
            pendingReport = state.getString("report");
            ArrayList<String> saved = state.getStringArrayList("collapsed");
            if (saved != null) collapsed.addAll(saved);
        }
        buildUi();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("selected", selected);
        state.putString("query", query);
        state.putString("report", pendingReport);
        state.putStringArrayList("collapsed", new ArrayList<>(collapsed));
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
        LinearLayout top = row();
        top.setPadding(dp(20), dp(12), dp(12), dp(8));
        LinearLayout titles = column();
        titles.addView(text("参数盒子", 24, INK, true));
        titles.addView(text(getString(R.string.app_subtitle), 12, MUTED, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button help = button("说明", false);
        help.setOnClickListener(v -> showInfo());
        top.addView(help);
        refreshButton = button("刷新", true);
        refreshButton.setOnClickListener(v -> refresh());
        top.addView(refreshButton);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), 0, dp(16), dp(24));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        scroll.addView(body);

        LinearLayout overview = column();
        overview.setPadding(dp(20), dp(20), dp(20), dp(20));
        overview.setBackground(rounded(0xFF123F40, 22));
        TextView tag = text("DEVICE INSIGHT  /  本机只读", 11, 0xFFA6DAD0, true);
        overview.addView(tag);
        hero = text(Build.MANUFACTURER + " " + Build.MODEL, 25, Color.WHITE, true);
        hero.setPadding(0, dp(12), 0, dp(8));
        overview.addView(hero);
        summary = text("Android " + Build.VERSION.RELEASE + "  ·  正在读取设备信息", 13,
                0xFFD0E6E3, false);
        summary.setLineSpacing(dp(4), 1f);
        overview.addView(summary);
        TextView note = text("无上传  ·  无广告  ·  不读取 IMEI", 12, 0xFFA6DAD0, false);
        note.setPadding(0, dp(15), 0, 0);
        overview.addView(note);
        body.addView(overview, spaced(-1, -2, 8, 16));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setHint("搜索参数，例如：温度、内存、刷新率");
        search.setContentDescription("搜索设备参数");
        search.setPadding(dp(16), dp(12), dp(16), dp(12));
        search.setBackground(rounded(Color.WHITE, 14));
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                renderCards();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        body.addView(search, spaced(-1, -2, 0, 12));

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        chipRow = row();
        filters.addView(chipRow);
        body.addView(filters, spaced(-1, -2, 0, 12));
        buildChips();

        LinearLayout actionRow = row();
        exportButton = button("保存报告", true);
        shareButton = button("分享报告", false);
        exportButton.setEnabled(false);
        shareButton.setEnabled(false);
        exportButton.setOnClickListener(v -> confirmReport(false));
        shareButton.setOnClickListener(v -> confirmReport(true));
        actionRow.addView(exportButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        shareLp.leftMargin = dp(10);
        actionRow.addView(shareButton, shareLp);
        body.addView(actionRow, spaced(-1, -2, 0, 12));

        LinearLayout stateRow = row();
        progress = new ProgressBar(this);
        stateRow.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
        status = text("正在读取…", 12, MUTED, false);
        status.setPadding(dp(8), 0, 0, 0);
        stateRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(stateRow, spaced(-1, -2, 0, 12));
        cards = column();
        body.addView(cards);
        TextView footer = text("参数来自 Android 系统公开接口。受系统、机型或厂商限制的值将明确标注；本应用不判断手机真伪，也不提供虚构跑分。", 12, MUTED, false);
        footer.setLineSpacing(dp(3), 1f);
        footer.setPadding(dp(6), dp(12), dp(6), dp(8));
        body.addView(footer);
    }

    private void buildChips() {
        chipRow.removeAllViews();
        String[][] filters = {{"all", "全部"}, {"hardware", "设备 / 系统"},
                {"performance", "内存 / 存储"}, {"battery", "电池"},
                {"display", "屏幕 / 传感器"}, {"network", "网络"},
                {"features", "能力 / 相机"}};
        for (String[] filter : filters) {
            boolean active = selected.equals(filter[0]);
            Button chip = button(filter[1], active);
            chip.setTextSize(12);
            chip.setSelected(active);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.rightMargin = dp(8);
            chipRow.addView(chip, lp);
            chip.setOnClickListener(v -> {
                selected = filter[0];
                buildChips();
                renderCards();
            });
        }
    }

    private boolean accepts(String id) {
        switch (selected) {
            case "hardware": return id.equals("device") || id.equals("system") || id.equals("cpu");
            case "performance": return id.equals("memory") || id.equals("storage");
            case "display": return id.equals("display") || id.equals("sensors");
            case "features": return id.equals("features") || id.equals("camera");
            case "battery": return id.equals("battery");
            case "network": return id.equals("network");
            default: return true;
        }
    }

    private void refresh() {
        final long request = ++collectionId;
        refreshButton.setEnabled(false);
        exportButton.setEnabled(false);
        shareButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText(sections.isEmpty() ? "正在读取本机参数…" : "正在刷新，下面暂为上次快照…");
        worker.execute(() -> {
            try {
                List<DeviceInspector.Section> result = DeviceInspector.collect(MainActivity.this);
                final long now = System.currentTimeMillis();
                main.post(() -> {
                    if (isDestroyed() || request != collectionId) return;
                    sections.clear();
                    sections.addAll(result);
                    capturedAt = now;
                    progress.setVisibility(View.GONE);
                    refreshButton.setEnabled(true);
                    exportButton.setEnabled(!sections.isEmpty());
                    shareButton.setEnabled(!sections.isEmpty());
                    int count = 0;
                    for (DeviceInspector.Section section : sections) count += section.metrics.size();
                    summary.setText("Android " + Build.VERSION.RELEASE + "  ·  "
                            + sections.size() + " 组参数  ·  " + count + " 个条目\n手动刷新快照，不是实时监测");
                    renderCards();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (isDestroyed() || request != collectionId) return;
                    progress.setVisibility(View.GONE);
                    refreshButton.setEnabled(true);
                    exportButton.setEnabled(!sections.isEmpty());
                    shareButton.setEnabled(!sections.isEmpty());
                    status.setText("读取失败（" + failure.getClass().getSimpleName() + "），点击刷新重试");
                });
            }
        });
    }

    private void renderCards() {
        if (cards == null) return;
        cards.removeAllViews();
        String keyword = query.trim().toLowerCase(Locale.ROOT);
        boolean searching = !keyword.isEmpty();
        int visibleCount = 0;
        for (DeviceInspector.Section section : sections) {
            if (!searching && !accepts(section.id)) continue;
            boolean sectionMatches = searching && (section.title + " " + section.subtitle)
                    .toLowerCase(Locale.ROOT).contains(keyword);
            List<DeviceInspector.Metric> shown = new ArrayList<>();
            for (DeviceInspector.Metric metric : section.metrics) {
                if (!searching || sectionMatches || (metric.label + " " + metric.value)
                        .toLowerCase(Locale.ROOT).contains(keyword)) shown.add(metric);
            }
            if (shown.isEmpty() && searching) continue;
            visibleCount += shown.size();
            LinearLayout card = column();
            card.setPadding(dp(16), dp(16), dp(16), dp(8));
            card.setBackground(rounded(Color.WHITE, 18));
            LinearLayout heading = row();
            LinearLayout headingText = column();
            headingText.addView(text(section.title, 18, INK, true));
            TextView sub = text(section.subtitle, 12, MUTED, false);
            sub.setPadding(0, dp(4), dp(8), 0);
            headingText.addView(sub);
            heading.addView(headingText, new LinearLayout.LayoutParams(0, -2, 1));
            boolean closed = !searching && collapsed.contains(section.id);
            TextView toggle = text(closed ? "+" : "−", 24, TEAL, false);
            toggle.setGravity(Gravity.CENTER);
            heading.addView(toggle, new LinearLayout.LayoutParams(dp(40), dp(48)));
            heading.setBackground(rounded(Color.WHITE, 12));
            heading.setContentDescription(section.title + (closed ? "，已折叠，点击展开" : "，已展开，点击折叠"));
            heading.setOnClickListener(v -> {
                if (searching) {
                    Toast.makeText(this, "搜索时自动展开匹配参数", Toast.LENGTH_SHORT).show();
                } else {
                    if (!collapsed.remove(section.id)) collapsed.add(section.id);
                    renderCards();
                }
            });
            card.addView(heading);
            if (!closed) {
                for (DeviceInspector.Metric metric : shown) {
                    View line = new View(this);
                    line.setBackgroundColor(0xFFECF0F3);
                    card.addView(line, spaced(-1, dp(1), 12, 10));
                    TextView label = text(metric.label, 12, MUTED, false);
                    card.addView(label);
                    TextView value = text(metric.value, 15, INK, false);
                    value.setLineSpacing(dp(3), 1f);
                    value.setPadding(0, dp(4), 0, dp(4));
                    value.setOnLongClickListener(v -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(
                                ClipData.newPlainText(metric.label, metric.label + "：" + metric.value));
                        Toast.makeText(this, "已复制参数", Toast.LENGTH_SHORT).show();
                        return true;
                    });
                    card.addView(value);
                }
            }
            cards.addView(card, spaced(-1, -2, 0, 12));
        }
        if (cards.getChildCount() == 0 && progress.getVisibility() != View.VISIBLE) {
            TextView empty = text(searching ? "没有匹配的参数，试试其他关键词。" : "暂无数据，点击右上角刷新。", 15, MUTED, false);
            empty.setPadding(dp(12), dp(32), dp(12), dp(32));
            cards.addView(empty);
        }
        if (progress.getVisibility() != View.VISIBLE && capturedAt > 0) {
            String when = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(capturedAt));
            status.setText("更新于 " + when + "  ·  " + visibleCount + " 项" + (searching ? "匹配（搜索全部分类）" : "  ·  长按值复制"));
        }
    }

    private void confirmReport(boolean share) {
        if (sections.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(share ? "分享本机快照" : "导出本机快照")
                .setMessage("报告包含全部分类，不受搜索和筛选影响，可能包含设备型号、设备名称、本地 IP 与 DNS。\n\n不含 IMEI、硬件序列号或 MAC。请只分享给可信的人。")
                .setNegativeButton("取消", null)
                .setPositiveButton(share ? "继续分享" : "选择保存位置", (dialog, which) -> {
                    String report = "参数盒子 / 采集时间：" + new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss Z", Locale.CHINA).format(new Date(capturedAt))
                            + "\n\n" + DeviceInspector.report(this, new ArrayList<>(sections));
                    if (share) shareReport(report);
                    else saveReport(report);
                }).show();
    }

    private void saveReport(String report) {
        pendingReport = report;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "phone-report-" + new SimpleDateFormat(
                "yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt");
        try {
            startActivityForResult(intent, EXPORT_REQUEST);
        } catch (android.content.ActivityNotFoundException unavailable) {
            pendingReport = null;
            Toast.makeText(this, "系统没有文件选择器，可使用分享报告", Toast.LENGTH_LONG).show();
        }
    }

    private void shareReport(String report) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "手机参数报告");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        try {
            startActivity(Intent.createChooser(intent, "分享设备参数报告"));
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, "没有可接收文本的应用", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST) return;
        final String report = pendingReport;
        pendingReport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || report == null) return;
        final Uri uri = data.getData();
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("无法打开保存位置");
                output.write(report.getBytes(StandardCharsets.UTF_8));
                main.post(() -> {
                    if (!isDestroyed()) Toast.makeText(this, "报告已保存", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!isDestroyed()) new AlertDialog.Builder(this).setTitle("保存未完成")
                            .setMessage("无法写入所选位置，请换一个目录重试。\n错误类型："
                                    + error.getClass().getSimpleName())
                            .setPositiveButton("知道了", null).show();
                });
            }
        });
    }

    private void showInfo() {
        new AlertDialog.Builder(this).setTitle("关于参数盒子")
                .setMessage("用于检查手机对应用开放的参数，不是跑分、验机或故障诊断工具。\n\n"
                        + "• 参数为手动刷新快照，不持续监控。\n"
                        + "• CPU 频率可能是瞬时值；部分厂商禁止读取。\n"
                        + "• 存储显示应用所在分区，不是厂商标称容量。\n"
                        + "• 电池读数不能用于推算真实健康度或额定容量。\n"
                        + "• 屏幕模式、窗口尺寸和物理尺寸需区分；系统 DPI 可能为逻辑值。\n"
                        + "• 传感器、相机和硬件特性仅展示系统报告的能力，不代表已测试正常。\n\n"
                        + "隐私：没有 INTERNET 权限、不上传数据、无需登录，不获取 IMEI、序列号、MAC、位置、通讯录或相机画面。仅保存/分享报告时由你选择去向。\n\n"
                        + "版本 1.0.0 · Android 8.0 及以上")
                .setPositiveButton("知道了", null).show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "不支持或系统未提供" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setMinWidth(dp(60));
        view.setMinHeight(dp(40));
        view.setMinimumHeight(dp(40));
        view.setMinimumWidth(dp(60));
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        android.content.res.ColorStateList colors = new android.content.res.ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xFF9AA9AD, primary ? Color.WHITE : TEAL});
        view.setTextColor(colors);
        view.setBackground(rounded(primary ? TEAL : PALE, 12));
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = dp(top);
        lp.bottomMargin = dp(bottom);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        ++collectionId;
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
