package com.exalted.vk640cursor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Tam ekran, dokunmaya kapali katman. Imleci (u,v) oraninda cizer; ucu tam konumdadir. */
public class CursorView extends View {
    volatile float u, v;
    volatile boolean show, tip;
    private float size = 28f * 3f;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    public CursorView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xFF000000);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setSizePx(size);
    }

    void setSizePx(float px) {
        size = px;
        stroke.setStrokeWidth(Math.max(2f, px / 14f));
        arrow.reset();
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 1.00f * px);
        arrow.lineTo(0.25f * px, 0.78f * px);
        arrow.lineTo(0.42f * px, 1.18f * px);
        arrow.lineTo(0.56f * px, 1.11f * px);
        arrow.lineTo(0.40f * px, 0.74f * px);
        arrow.lineTo(0.72f * px, 0.74f * px);
        arrow.close();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!show) return;
        fill.setColor(tip ? 0xFFFFB300 : 0xFFFFFFFF);
        canvas.save();
        canvas.translate(u * getWidth(), v * getHeight());
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
        canvas.restore();
    }
}
