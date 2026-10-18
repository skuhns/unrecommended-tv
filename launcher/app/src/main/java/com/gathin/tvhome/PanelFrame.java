package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/** Root of the Picture panel: turns Back into "close", which an overlay window doesn't get for free. */
public class PanelFrame extends FrameLayout {

    private Runnable onBack;

    public PanelFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setOnBack(Runnable r) {
        onBack = r;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && onBack != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) onBack.run();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
