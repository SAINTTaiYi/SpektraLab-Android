package app.spektralab.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 值选择行（56dp）：左标签 12sp，右当前值 14sp，末尾一个 12dp chevron；
 * 点一下打开 Sheet.list，选中即生效。
 *
 * 替换 4 个平台 Spinner，但**保留 AdapterView.OnItemSelectedListener 的签名**，
 * 于是 MainActivity 里原有的选中逻辑（反转片不能印相纸、画幅换算、片门 id 映射）
 * 一行都不用改。
 */
public final class UiPicker {

    private final Context ctx;
    private final FrameLayout frame;
    private final LinearLayout row;
    private final TextView labelView;
    private final TextView valueView;
    private final List<String> items = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();

    private int index = 0;
    private boolean suppress = false;
    private AdapterView.OnItemSelectedListener legacy;
    private boolean enabled = true;

    private UiPicker(Context c, String label) {
        this.ctx = c;
        Tokens.loadFonts(c);

        frame = new FrameLayout(c);

        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);

        labelView = Tokens.text(c, label, Tokens.SP_BODY, Tokens.TEXT_2, Tokens.han(), 0f, false);
        labelView.setSingleLine(true);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        valueView = Tokens.text(c, "", Tokens.SP_FIELD_VALUE, Tokens.TEXT_1,
                Tokens.han(), 0f, false);
        valueView.setSingleLine(true);
        valueView.setGravity(Gravity.END);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = Tokens.dp(c, 12);
        row.addView(valueView, vlp);

        View chevron = new Chrome.Glyph(c, Tokens.TEXT_3, Chrome.Glyph.CHEVRON_DOWN);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Tokens.dp(c, 12), Tokens.dp(c, 12));
        clp.leftMargin = Tokens.dp(c, 8);
        row.addView(chevron, clp);

        frame.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, Tokens.ROW_FIELD)));
        frame.addView(Chrome.hairline(c), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Tokens.dp(c, 1)),
                Gravity.BOTTOM));

        frame.setClickable(true);
        frame.setFocusable(true);
        frame.setMinimumHeight(Tokens.dp(c, Tokens.ROW_FIELD));
        frame.setOnClickListener(v -> open());
    }

    public static UiPicker field(Context c, String label) {
        return new UiPicker(c, label);
    }

    public View view() { return frame; }

    public String label() { return labelView.getText().toString(); }

    // ------------------------------------------------------------ Spinner 兼容 API

    public void setItems(List<String> v) {
        setItems(v, null);
    }

    /** 带右侧短标签（负片 / 反转 / 电影 …）。 */
    public void setItems(List<String> v, List<String> subItems) {
        items.clear();
        subs.clear();
        if (v != null) items.addAll(v);
        if (subItems != null) subs.addAll(subItems);
        if (index >= items.size()) index = Math.max(0, items.size() - 1);
        render();
    }

    public List<String> getItems() { return new ArrayList<>(items); }

    /** 与 Spinner.setSelection(i, animate) 一致：会触发一次 onItemSelected。 */
    public void setSelection(int position, boolean animate) {
        if (items.isEmpty()) return;
        index = Math.max(0, Math.min(items.size() - 1, position));
        render();
        if (!suppress) fire();
    }

    /** 设初值但不回调（初始化时用）。 */
    public void setSelectionSilently(int position) {
        suppress = true;
        try { setSelection(position, false); } finally { suppress = false; }
    }

    public int getSelectedItemPosition() { return index; }

    public String getSelectedItem() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener l) { this.legacy = l; }

    public void setEnabled(boolean on) {
        enabled = on;
        frame.setEnabled(on);
        frame.setAlpha(on ? 1f : Tokens.DISABLED_ALPHA);
    }

    private void fire() {
        if (legacy != null) legacy.onItemSelected(null, frame, index, index);
        frame.setContentDescription(label() + "，" + currentText() + "，点按选择");
    }

    private String currentText() { return valueView.getText().toString(); }

    private void render() {
        valueView.setText(items.isEmpty() ? "" : items.get(index));
    }

    private void open() {
        if (!enabled || items.isEmpty()) return;
        Sheet.list(ctx, labelView.getText().toString(), items, subs, index, picked -> {
            if (picked == index) return;
            index = picked;
            render();
            fire();
        });
    }
}
