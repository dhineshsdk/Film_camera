package com.s24film.camera;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen film stock browser: category tabs, search, favourites and recents over all 120 looks.
 * Self-contained (built in code) so it does not depend on the camera screen layout.
 */
final class StockBrowser {
    interface Listener { void onLookPicked(int lookIndex); }

    // Tabs: 0 = All, 1 = Favourites, 2 = Recent, 3.. = categories 0..6
    private static final String[] TAB_LABELS = {
        "All", "★ Favourites", "Recent", "C-41", "ECN-2", "Slide", "B&W", "Print", "Cinema", "Fuji Sims"
    };

    private final Context ctx;
    private final LookCatalog catalog;
    private final Listener listener;
    private final float d;
    private final List<LookCatalog.Look> shown = new ArrayList<>();
    private final TextView[] tabViews = new TextView[TAB_LABELS.length];
    private int tab = 0;
    private String query = "";
    private int activeLook;
    private Adapter adapter;
    private TextView countView;

    StockBrowser(Context ctx, LookCatalog catalog, Listener listener) {
        this.ctx = ctx;
        this.catalog = catalog;
        this.listener = listener;
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    void show(int currentLookIndex) {
        activeLook = currentLookIndex;
        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        root.setPadding(dp(12), dp(28), dp(12), dp(8));

        // header: title + count + close
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Film stocks", 20, 0xFFFFFFFF, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        countView = label("", 13, 0xFF9A9A9A, false);
        head.addView(countView);
        TextView close = label("  ✕  ", 20, 0xFFFFFFFF, true);
        close.setOnClickListener(new Click(this, Click.CLOSE, dlg, -1));
        head.addView(close);
        root.addView(head);

        // search
        EditText search = new EditText(ctx);
        search.setHint("Search name, brand or ISO");
        search.setHintTextColor(0xFF777777);
        search.setTextColor(0xFFFFFFFF);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setBackground(round(0xFF1E1E1E, 12));
        search.addTextChangedListener(new SearchWatcher(this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        root.addView(search, sp);

        // category tabs
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(ctx);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int ti = i;
            TextView t = label(TAB_LABELS[i], 14, 0xFFDDDDDD, true);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            t.setOnClickListener(new Click(this, Click.TAB, dlg, ti));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            tabs.addView(t, lp);
            tabViews[i] = t;
        }
        hs.addView(tabs);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(8);
        hp.bottomMargin = dp(6);
        root.addView(hs, hp);

        // list
        ListView list = new ListView(ctx);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        adapter = new Adapter(dlg);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg.setContentView(root);
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        refresh();
        dlg.show();

        // open on the tab holding the active look; jump to it
        int pos = shown.indexOf(catalog.get(activeLook));
        if (pos > 0) list.setSelection(Math.max(0, pos - 2));
    }

    static final class SearchWatcher implements TextWatcher {
        private final StockBrowser b;
        SearchWatcher(StockBrowser b) { this.b = b; }
        @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
        @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
        @Override public void afterTextChanged(Editable s) {
            b.query = s.toString().trim().toLowerCase(Locale.US);
            b.refresh();
        }
    }

    static final class Click implements View.OnClickListener {
        static final int CLOSE = 0, TAB = 1, STAR = 2, PICK = 3;
        private final StockBrowser b;
        private final int action, arg;
        private final Dialog dlg;
        Click(StockBrowser b, int action, Dialog dlg, int arg) {
            this.b = b; this.action = action; this.dlg = dlg; this.arg = arg;
        }
        @Override public void onClick(View v) {
            if (action == CLOSE) {
                dlg.dismiss();
            } else if (action == TAB) {
                b.tab = arg;
                b.refresh();
            } else if (action == STAR) {
                b.catalog.toggleFavourite(arg);
                b.refresh();
            } else {
                b.catalog.addRecent(arg);
                b.catalog.setLastUsed(arg);
                b.listener.onLookPicked(arg);
                dlg.dismiss();
            }
        }
    }

    private void refresh() {
        shown.clear();
        List<Integer> recents = catalog.recents();
        if (tab == 2) {
            for (int idx : recents) { LookCatalog.Look l = catalog.get(idx); if (l != null && matches(l)) shown.add(l); }
        } else {
            for (LookCatalog.Look l : catalog.looks) {
                if (tab == 1 && !catalog.isFavourite(l.index)) continue;
                if (tab >= 3 && l.category != tab - 3) continue;
                if (matches(l)) shown.add(l);
            }
        }
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == tab;
            tabViews[i].setTextColor(sel ? 0xFF101010 : 0xFFDDDDDD);
            tabViews[i].setBackground(round(sel ? 0xFFFFC857 : 0xFF222222, 18));
        }
        countView.setText(String.format(Locale.US, "%d of %d", shown.size(), catalog.looks.size()));
        adapter.notifyDataSetChanged();
    }

    private boolean matches(LookCatalog.Look l) {
        if (query.isEmpty()) return true;
        String key = l.searchKey();
        for (String w : query.split("\\s+")) if (!key.contains(w)) return false;
        return true;
    }

    private final class Adapter extends BaseAdapter {
        private final Dialog dlg;
        Adapter(Dialog dlg) { this.dlg = dlg; }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).index; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            final LookCatalog.Look l = shown.get(pos);
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(4), dp(6));
            boolean active = l.index == activeLook;
            row.setBackground(round(active ? 0xFF2A2415 : 0x00000000, 12));

            // swatch: category colour with the ISO on it
            TextView sw = label(l.iso > 0 ? String.valueOf(l.iso) : "—", 12, 0xFFFFFFFF, true);
            sw.setGravity(Gravity.CENTER);
            sw.setBackground(round(LookCatalog.CATEGORY_COLORS[l.category], 10));
            row.addView(sw, new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(label(l.name, 16, 0xFFFFFFFF, true));
            col.addView(label(LookCatalog.CATEGORY_NAMES[l.category] + " · " + l.isoText(),
                12, 0xFF9A9A9A, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView star = label(catalog.isFavourite(l.index) ? "★" : "☆", 24,
                catalog.isFavourite(l.index) ? 0xFFFFC857 : 0xFF777777, false);
            star.setGravity(Gravity.CENTER);
            star.setOnClickListener(new Click(StockBrowser.this, Click.STAR, dlg, l.index));
            row.addView(star, new LinearLayout.LayoutParams(dp(48), dp(48)));

            row.setOnClickListener(new Click(StockBrowser.this, Click.PICK, dlg, l.index));
            return row;
        }
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) { return Math.round(v * d); }
}
