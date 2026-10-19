package dev.atvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    // Dock apps, left to right. Override without rebuilding: one package name per line in
    // /sdcard/Android/data/dev.atvhome/files/dock.txt  (a wallpaper.jpg in the same folder replaces the black background)
    static final String[] DOCK = {
            "com.google.android.youtube.tv", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "in.startv.hotstar"};

    static final int COLS = 4, TOP = 0, DOCKZ = 1, GRID = 2, MENU = 3;
    static final float LIFT = 1.08f;

    static class App { String pkg, label; Intent launch; Drawable art; boolean banner; }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art; final TextView label;
        VH(View v, ImageView a, TextView l) { super(v); art = a; label = l; }
    }

    final List<App> dock = new ArrayList<>(), grid = new ArrayList<>();
    final List<Runnable> menuActions = new ArrayList<>();
    final Map<String, Integer> glowCache = new HashMap<>();
    float d;
    int W, H, tileW, tileH, gap, colW, left0, bandY, rowH;
    Typeface thin, light, regular, medium;
    FrameLayout content;
    Glass glass;
    Glow glow;
    View band;
    TextView greeting, clock, date, title;
    LinearLayout pills, menu;
    ImageView[] dockTiles = new ImageView[0];
    RecyclerView rv;
    int zone = DOCKZ, idx = 0, lastDock = 0, lastTop = 0, glowColor = 0xFF333333;
    View lifted; TextView shownLabel;
    ValueAnimator glowAnim;
    ConnectivityManager.NetworkCallback netCb;

    final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { tick(); }
    };
    final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    int dp(float x) { return Math.round(x * d); }

    Typeface font(int wght) {
        return new Typeface.Builder(getAssets(), "Outfit.ttf").setFontVariationSettings("'wght' " + wght).build();
    }

    TextView text(Typeface tf, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false);
        return t;
    }

    static FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x; lp.topMargin = y;
        return lp;
    }

    static GradientDrawable rounded(int fill, int stroke, float radius, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(strokeW, stroke);
        return g;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        d = getResources().getDisplayMetrics().density;
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        tileW = dp(194); tileH = tileW * 9 / 16; gap = dp(24); colW = tileW + gap;
        left0 = (W - COLS * tileW - (COLS - 1) * gap) / 2;
        int bandH = tileH + dp(44);
        bandY = H - dp(24) - bandH;
        rowH = tileH + dp(56);
        thin = font(100); light = font(300); regular = font(400); medium = font(500);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wallpaper(root);
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(W, ViewGroup.LayoutParams.WRAP_CONTENT));

        glow = new Glow(this, left0 + dp(170), bandY - dp(40), dp(420));
        content.addView(glow, at(W, H + dp(100), 0, 0));

        greeting = text(regular, 20, 0xFF8E8E93);
        content.addView(greeting, at(-2, -2, left0, dp(40)));
        clock = text(thin, 96, Color.WHITE);
        content.addView(clock, at(-2, -2, left0 - dp(4), dp(64)));
        date = text(light, 22, 0xFFC7C7CC);
        content.addView(date, at(-2, -2, left0, dp(170)));

        pills = new LinearLayout(this);
        String[] names = {"Inputs", "Wi-Fi", "Settings"};
        for (String n : names) {
            TextView p = text(medium, 16, 0xF2FFFFFF);
            p.setText(n);
            p.setPadding(dp(20), dp(9), dp(20), dp(9));
            p.setBackground(rounded(0x24FFFFFF, 0x2EFFFFFF, dp(40), Math.max(1, dp(1))));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(12);
            pills.addView(p, lp);
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = dp(44); plp.rightMargin = left0;
        content.addView(pills, plp);

        title = text(light, 56, 0xFFF2F2F7);
        title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        int sh = dp(36); // room for the glow shadow, which is clipped to the view bounds
        title.setPadding(sh, sh, sh, sh);
        content.addView(title, at(W - 2 * left0 + 2 * sh, -2, left0 - dp(2) - sh, bandY - dp(20) - dp(70) - sh));

        band = new View(this);
        band.setBackground(rounded(0x17FFFFFF, 0x0CFFFFFF, dp(30), Math.max(1, dp(1))));
        content.addView(band, at(COLS * tileW + (COLS - 1) * gap + dp(48), bandH, left0 - dp(24), bandY));

        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, COLS));
        rv.setItemAnimator(null);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setNestedScrollingEnabled(false);
        rv.setClipChildren(false);
        rv.setAdapter(new RecyclerView.Adapter<VH>() {
            @Override public VH onCreateViewHolder(ViewGroup p, int t) {
                LinearLayout ll = new LinearLayout(MainActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(Gravity.CENTER_HORIZONTAL);
                ll.setClipChildren(false);
                ll.setLayoutParams(new RecyclerView.LayoutParams(-1, rowH));
                ImageView iv = art();
                ll.addView(iv, new LinearLayout.LayoutParams(tileW, tileH));
                TextView l = text(regular, 16, 0xFFE5E5EA);
                l.setSingleLine(); l.setEllipsize(TextUtils.TruncateAt.END);
                l.setGravity(Gravity.CENTER); l.setAlpha(0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(colW, -2);
                lp.topMargin = dp(16);
                ll.addView(l, lp);
                return new VH(ll, iv, l);
            }
            @Override public void onBindViewHolder(VH h, int i) {
                bindArt(h.art, grid.get(i));
                h.label.setText(grid.get(i).label);
                boolean f = zone == GRID && idx == i;
                h.label.setAlpha(f ? 1 : 0);
                h.art.setScaleX(f ? LIFT : 1); h.art.setScaleY(f ? LIFT : 1);
            }
            @Override public int getItemCount() { return grid.size(); }
        });
        content.addView(rv, at(COLS * colW, 0, left0 - gap / 2, H));

        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        menu.setBackground(rounded(0xEB1C1C1E, 0x22FFFFFF, dp(18), Math.max(1, dp(1))));
        menu.setVisibility(View.GONE);
        content.addView(menu, at(-2, -2, 0, 0));

        glass = new Glass(this);
        content.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        zone = sp.getInt("zone", DOCKZ); idx = sp.getInt("idx", 0);
        lastDock = sp.getInt("lastDock", 0);
        if (zone == MENU) { zone = TOP; idx = 0; }

        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED); tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeRx, tf);
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED); pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);
        watchNetwork();
        tick();
        reload();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(timeRx); unregisterReceiver(pkgRx);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb);
        super.onDestroy();
    }

    @Override protected void onPause() {
        super.onPause();
        getPreferences(MODE_PRIVATE).edit().putInt("zone", zone).putInt("idx", idx)
                .putInt("lastDock", lastDock).apply();
    }

    @Override protected void onResume() { super.onResume(); tick(); }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (zone == MENU) closeMenu();
    }

    File userFile(String name) { return new File(getExternalFilesDir(null), name); }

    void wallpaper(FrameLayout root) {
        File f = userFile("wallpaper.jpg");
        if (!f.exists()) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = Math.max(1, Math.min(o.outWidth / W, o.outHeight / H));
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565; // half the RAM of ARGB
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
        iv.setForeground(new ColorDrawable(0x66000000)); // keep text readable
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
    }

    List<String> dockOrder() {
        try {
            File f = userFile("dock.txt");
            if (f.exists()) {
                List<String> out = new ArrayList<>();
                for (String s : Files.readAllLines(f.toPath())) if (!s.trim().isEmpty()) out.add(s.trim());
                return out;
            }
        } catch (Exception ignored) { }
        return Arrays.asList(DOCK);
    }

    void reload() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> all = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName()) || all.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = ri.loadLabel(pm).toString();
            a.launch = new Intent(q).setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable bn = ai.loadBanner(pm);
            a.banner = bn != null;
            a.art = a.banner ? bn : ai.loadIcon(pm);
            all.put(a.pkg, a);
        }
        dock.clear(); grid.clear();
        for (String p : dockOrder()) { App a = all.remove(p); if (a != null && dock.size() < COLS) dock.add(a); }
        grid.addAll(all.values());
        grid.sort((x, y) -> x.label.compareToIgnoreCase(y.label));

        for (ImageView t : dockTiles) content.removeView(t);
        dockTiles = new ImageView[dock.size()];
        for (int i = 0; i < dock.size(); i++) {
            ImageView iv = art();
            bindArt(iv, dock.get(i));
            dockTiles[i] = iv;
            content.addView(iv, content.indexOfChild(menu), at(tileW, tileH, left0 + i * colW, bandY + dp(22)));
        }
        band.setVisibility(dock.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        int rows = (grid.size() + COLS - 1) / COLS;
        rv.getLayoutParams().height = Math.max(1, rows) * rowH;
        content.getLayoutParams().height = H + Math.max(1, rows) * rowH + dp(40); // taller than the screen; scrolled by translation
        rv.getAdapter().notifyDataSetChanged();
        lifted = null; shownLabel = null;
        if (!dock.isEmpty()) showTitle(dock.get(Math.min(lastDock, dock.size() - 1))); // even when focus restores to the top bar
        afterLayout(() -> focus(zone, idx));
    }

    ImageView art() {
        ImageView iv = new ImageView(this);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        iv.setBackgroundColor(0xFF1C1C1E);
        return iv;
    }

    void bindArt(ImageView iv, App a) {
        iv.setImageDrawable(a.art);
        iv.setScaleType(a.banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int p = a.banner ? 0 : dp(24);
        iv.setPadding(p, p, p, p);
    }

    void afterLayout(Runnable r) {
        content.requestLayout();
        content.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                content.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
    }

    // ---- focus ----

    void focus(int z, int i) {
        if (z == DOCKZ && dock.isEmpty()) { z = GRID; i = 0; }
        if (z == GRID && grid.isEmpty()) { z = dock.isEmpty() ? TOP : DOCKZ; i = 0; }
        int n = z == TOP ? pills.getChildCount() : z == DOCKZ ? dock.size() : z == GRID ? grid.size() : menu.getChildCount();
        i = Math.max(0, Math.min(i, n - 1));
        View target; TextView label = null; float scale = 1, radius;
        if (z == TOP) { target = pills.getChildAt(i); radius = target.getHeight() / 2f; lastTop = i; }
        else if (z == MENU) { target = menu.getChildAt(i); radius = dp(10); }
        else if (z == DOCKZ) { target = dockTiles[i]; scale = LIFT; radius = dp(12) * LIFT; lastDock = i; showTitle(dock.get(i)); }
        else {
            VH h = (VH) rv.findViewHolderForAdapterPosition(i);
            if (h == null) { final int fz = z, fi = i; afterLayout(() -> focus(fz, fi)); return; }
            target = h.art; label = h.label; scale = LIFT; radius = dp(12) * LIFT;
        }
        zone = z; idx = i;

        if (lifted != null && lifted != target) lifted.animate().scaleX(1).scaleY(1).setDuration(180).start();
        if (shownLabel != null && shownLabel != label) shownLabel.animate().alpha(0).setDuration(120).start();
        if (scale > 1) target.animate().scaleX(scale).scaleY(scale).setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
        if (label != null) label.animate().alpha(1).setDuration(200).start();
        lifted = target; shownLabel = label;

        float ty = z == GRID ? -(bandY - dp(24)) - (i / COLS) * rowH : 0;
        content.animate().translationY(ty).setDuration(340).setInterpolator(new DecelerateInterpolator(2)).start();

        Rect r = new Rect();
        target.getDrawingRect(r);
        content.offsetDescendantRectToMyCoords(target, r);
        float pad = scale > 1 ? dp(4) : dp(2);
        float gx = r.width() * (scale - 1) / 2 + pad, gy = r.height() * (scale - 1) / 2 + pad;
        glass.moveTo(new RectF(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy), radius + pad);
    }

    void showTitle(App a) {
        title.setText(a.label);
        title.setAlpha(0.3f);
        title.animate().alpha(1).setDuration(250).start();
        Integer c = glowCache.get(a.pkg);
        if (c == null) { c = sampleColor(a.art); glowCache.put(a.pkg, c); }
        title.setShadowLayer(dp(28), 0, 0, (c & 0xFFFFFF) | 0xAA000000);
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofArgb(glowColor, c);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> {
            glowColor = (int) va.getAnimatedValue();
            glow.setColor(glowColor);
        });
        glowAnim.start();
    }

    // saturation-weighted average, so a red logo on a white banner gives red, not pink-grey
    static int sampleColor(Drawable src) {
        Drawable dr = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
        Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        dr.setBounds(0, 0, 32, 18);
        dr.draw(new Canvas(b));
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, w = 0;
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
            int p = b.getPixel(x, y);
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            double wt = hsv[1] * hsv[2] + 0.02;
            r += Color.red(p) * wt; g += Color.green(p) * wt; bl += Color.blue(p) * wt; w += wt;
        }
        b.recycle();
        if (w == 0) return 0xFF5E5CE6;
        Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w)), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }

    // ---- keys: navigation is computed here, not by Android's focus search, so it is deterministic ----

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean nav = ok || k == KeyEvent.KEYCODE_BACK || (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT);
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) handle(k, ok);
        return true;
    }

    void handle(int k, boolean ok) {
        int col = idx % COLS;
        switch (zone) {
            case TOP:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && idx > 0) focus(TOP, idx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && idx < pills.getChildCount() - 1) focus(TOP, idx + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN) focus(DOCKZ, lastDock);
                else if (ok) pill(idx);
                break;
            case DOCKZ:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && idx > 0) focus(DOCKZ, idx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && idx < dock.size() - 1) focus(DOCKZ, idx + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) focus(TOP, lastTop);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !grid.isEmpty()) focus(GRID, Math.min(idx, grid.size() - 1));
                else if (ok) launch(dock.get(idx));
                break;
            case GRID:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) focus(GRID, idx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && col < COLS - 1 && idx + 1 < grid.size()) focus(GRID, idx + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    if (idx >= COLS) focus(GRID, idx - COLS); else focus(DOCKZ, Math.min(col, dock.size() - 1));
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (idx + COLS < grid.size()) focus(GRID, idx + COLS);
                    else if (idx / COLS < (grid.size() - 1) / COLS) focus(GRID, grid.size() - 1); // short last row
                } else if (k == KeyEvent.KEYCODE_BACK) focus(DOCKZ, lastDock);
                else if (ok) launch(grid.get(idx));
                break;
            case MENU:
                if (k == KeyEvent.KEYCODE_DPAD_UP && idx > 0) focus(MENU, idx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && idx < menu.getChildCount() - 1) focus(MENU, idx + 1);
                else if (ok) { Runnable r = menuActions.get(idx); closeMenu(); r.run(); }
                else if (k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) closeMenu();
                break;
        }
    }

    void launch(App a) { start(a.launch); }

    void start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    void pill(int i) {
        if (i == 0) openMenu();
        else start(new Intent(i == 1 ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_SETTINGS));
    }

    // ---- inputs dropdown, switched through the TV input framework ----

    void openMenu() {
        menu.removeAllViews(); menuActions.clear();
        TvInputManager tim = getSystemService(TvInputManager.class);
        List<TvInputInfo> ins = new ArrayList<>();
        String tuner = null;
        for (TvInputInfo t : tim.getTvInputList()) {
            if (t.getParentId() != null || t.isHidden(this)) continue;
            if (t.isPassthroughInput()) ins.add(t);
            else if (t.getType() == TvInputInfo.TYPE_TUNER && tuner == null) tuner = t.getId();
        }
        ins.sort((a, b) -> {
            int ha = a.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1, hb = b.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
            return ha != hb ? ha - hb : label(a).compareTo(label(b));
        });
        for (TvInputInfo t : ins) {
            String id = t.getId();
            menuItem(label(t), () -> start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))));
        }
        // the MediaTek TV app takes a passthrough URI naming the tuner and switches to it (a channel-list URI does nothing)
        if (tuner != null) {
            String tunerId = tuner;
            menuItem("Live TV", () -> start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tunerId))));
        }

        View inputs = pills.getChildAt(0);
        Rect r = new Rect();
        inputs.getDrawingRect(r);
        content.offsetDescendantRectToMyCoords(inputs, r);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.leftMargin = Math.min(r.left, W - left0 - dp(240)); lp.topMargin = r.bottom + dp(10);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0); menu.setTranslationY(-dp(8));
        menu.animate().alpha(1).translationY(0).setDuration(180).start();
        zone = MENU;
        afterLayout(() -> focus(MENU, 0));
    }

    String label(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        return (c != null && c.length() > 0 ? c : t.loadLabel(this)).toString();
    }

    void menuItem(String s, Runnable action) {
        TextView t = text(regular, 17, 0xFFF2F2F7);
        t.setText(s);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(18), 0, dp(18), 0);
        t.setMinWidth(dp(220));
        menu.addView(t, new LinearLayout.LayoutParams(-1, dp(42)));
        menuActions.add(action);
    }

    void closeMenu() {
        menu.setVisibility(View.GONE);
        zone = TOP;
        focus(TOP, 0);
    }

    // ---- clock + network: broadcast/callback driven, nothing polls ----

    void tick() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening");
    }

    void watchNetwork() {
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                String s = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? ssid()
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Network";
                runOnUiThread(() -> setNet(s));
            }
            @Override public void onLost(Network n) { runOnUiThread(() -> setNet("Offline")); }
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCb);
    }

    @SuppressWarnings("deprecation")
    String ssid() {
        String s = getSystemService(WifiManager.class).getConnectionInfo().getSSID();
        return s == null || s.contains("unknown ssid") ? "Wi-Fi" : s.replace("\"", "");
    }

    void setNet(String s) {
        TextView p = (TextView) pills.getChildAt(1);
        if (s.equals(p.getText().toString())) return;
        p.setText(s);
        if (zone == TOP) afterLayout(() -> focus(TOP, idx)); // pill widths changed under the highlight
    }

    // ambient glow behind the dock title: a squashed radial gradient (cheap; no blur)
    static class Glow extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float cx, cy, r;
        Glow(Context c, float cx, float cy, float r) { super(c); this.cx = cx; this.cy = cy; this.r = r; }
        void setColor(int color) {
            int rgb = color & 0xFFFFFF;
            p.setShader(new android.graphics.RadialGradient(cx, cy, r,
                    new int[]{rgb | 0x8C000000, rgb | 0x26000000, rgb}, null, Shader.TileMode.CLAMP));
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            if (p.getShader() == null) return;
            c.save();
            c.scale(1, 0.55f, cx, cy);
            c.drawCircle(cx, cy, r, p);
            c.restore();
        }
    }

    // ---- the single glass highlight ----

    class Glass extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
                sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        float rad, radFrom, radTo;
        ValueAnimator anim;

        Glass(Context c) {
            super(c);
            fill.setColor(0x26FFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(1.5f));
            edge.setColor(0x70FFFFFF);
        }

        void moveTo(RectF r, float radius) {
            if (anim != null) anim.cancel();
            if (cur.isEmpty()) { cur.set(r); rad = radius; invalidate(); return; }
            from.set(cur); to.set(r); radFrom = rad; radTo = radius;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(320);
            anim.setInterpolator(new OvershootInterpolator(1.1f)); // the "spring"
            anim.addUpdateListener(va -> {
                float t = (float) va.getAnimatedValue();
                cur.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                        from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
                rad = radFrom + (radTo - radFrom) * t;
                invalidate();
            });
            anim.start();
        }

        @Override protected void onDraw(Canvas c) {
            if (cur.isEmpty()) return;
            sheen.setShader(new LinearGradient(0, cur.top, 0, cur.top + cur.height() * 0.6f,
                    0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cur, rad, rad, fill);
            c.drawRoundRect(cur, rad, rad, sheen);
            c.drawRoundRect(cur, rad, rad, edge);
        }
    }
}
