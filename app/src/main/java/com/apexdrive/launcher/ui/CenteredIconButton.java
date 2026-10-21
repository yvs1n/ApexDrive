package com.apexdrive.launcher.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatButton;

/**
 * Button whose start icon sits next to the text, with the pair centred, as the
 * design-system buttons are drawn. A plain Button pins drawableStart to the edge.
 */
public class CenteredIconButton extends AppCompatButton {

    private final int minSidePadding;

    public CenteredIconButton(Context context) {
        this(context, null);
    }

    public CenteredIconButton(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.appcompat.R.attr.buttonStyle);
    }

    public CenteredIconButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        minSidePadding = getPaddingLeft();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Drawable start = getCompoundDrawablesRelative()[0];
        if (start == null) return;
        CharSequence text = getText();
        float textWidth = text == null ? 0 : getPaint().measureText(text.toString());
        int content = Math.round(start.getIntrinsicWidth() + getCompoundDrawablePadding() + textWidth);
        int side = Math.max(minSidePadding, (getMeasuredWidth() - content) / 2);
        if (side != getPaddingLeft() || side != getPaddingRight()) {
            setPadding(side, getPaddingTop(), side, getPaddingBottom());
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
