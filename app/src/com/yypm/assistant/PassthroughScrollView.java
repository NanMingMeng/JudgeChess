package com.yypm.assistant;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * v4.81: holo card background (MELLO theme).
 * Blank areas do not consume touch, so events fall through to the WebView
 * below for card rotation. Only tappable controls consume their own area.
 */
public final class PassthroughScrollView extends ScrollView {
    public PassthroughScrollView(Context c) { super(c); }
    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return false; }
    @Override public boolean onTouchEvent(MotionEvent e) { return false; }
}
