package com.nightabyss.hour30;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;

/** Two settings, written where LSPosed lets the hooked processes read them (XSharedPreferences). */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs;
        boolean active = true;
        try {
            //noinspection deprecation
            prefs = getSharedPreferences(Config.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
            active = false;
        }
        final SharedPreferences p = prefs;

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        if (!active) {
            TextView warn = new TextView(this);
            warn.setText(R.string.inactive);
            warn.setPadding(0, 0, 0, pad);
            root.addView(warn);
        }

        Switch enabled = new Switch(this);
        enabled.setText(R.string.enabled);
        enabled.setTextSize(18);
        enabled.setChecked(p.getBoolean(Config.KEY_ENABLED, true));
        enabled.setOnCheckedChangeListener((v, on) -> p.edit().putBoolean(Config.KEY_ENABLED, on).apply());
        root.addView(enabled);

        TextView label = new TextView(this);
        label.setText(R.string.boundary);
        label.setTextSize(18);
        label.setPadding(0, pad, 0, 0);
        root.addView(label);

        TextView hint = new TextView(this);
        hint.setText(R.string.boundary_hint);
        root.addView(hint);

        NumberPicker hour = new NumberPicker(this);
        hour.setMinValue(1);
        hour.setMaxValue(12);
        hour.setValue(p.getInt(Config.KEY_BOUNDARY, Config.DEFAULT_BOUNDARY));
        hour.setOnValueChangedListener((v, o, n) -> p.edit().putInt(Config.KEY_BOUNDARY, n).apply());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(hour, lp);

        setContentView(root);
    }
}
