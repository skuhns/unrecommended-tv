package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * Stays at the top while the focus is on the first screen (clock, top bar, favourites); below that,
 * scrolls so the focused tile sits a little below the middle of the screen.
 */
public class CenteringScrollView extends ScrollView {

    public CenteringScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) return 0;
        int max = Math.max(0, getChildAt(0).getHeight() - getHeight());
        if (rect.bottom <= getHeight()) return -getScrollY();
        int target = rect.centerY() - (int) (getHeight() * 0.55f);
        target = Math.max(0, Math.min(target, max));
        return target - getScrollY();
    }
}
