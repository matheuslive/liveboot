/* Copyright (C) 2011-2024 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.chainfire.liveboot.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.Map;

import eu.chainfire.libcfsurface.gl.GLHelper;
import eu.chainfire.libcfsurface.gl.GLTextManager;
import eu.chainfire.libcfsurface.gl.GLTextureManager;
import eu.chainfire.liveboot.shell.ColorSpan;

/**
 * GLTextManager paints each line in a single color. This subclass adds lines
 * whose characters carry individual colors and styles, which is what matching
 * dmesg's output requires - it colors the timestamp, the subsystem prefix and
 * the message body differently within one line.
 *
 * It works by intercepting getBitmap(), which the base class calls once per
 * line to rasterize it. Since the base class keeps the exact String instance
 * handed to add(), the spans for a line are looked up by object identity.
 * Should that ever stop holding, the lookup misses and the line is drawn in
 * its single color by the base class, so the failure mode is a loss of color,
 * not a crash.
 */
public class SpanTextManager extends GLTextManager {
    /** Lines currently held by the base class, plus headroom for those being added */
    private static final int SPAN_CACHE_FACTOR = 4;

    private final Map<String, ColorSpan[]> mSpans = new IdentityHashMap<String, ColorSpan[]>();
    private final LinkedList<String> mSpanOrder = new LinkedList<String>();

    public SpanTextManager(GLTextureManager textureManager, GLHelper helper, int width, int height, int lineHeight) {
        super(textureManager, helper, width, height, lineHeight);
    }

    /**
     * As add(text, color, wordWrap), but coloring individual runs of the line.
     * Passing null spans is equivalent to the base implementation.
     */
    public void add(String text, int color, boolean wordWrap, ColorSpan[] spans) {
        if ((spans == null) || (spans.length == 0) || (text == null) || text.equals("")) {
            super.add(text, color, wordWrap);
            return;
        }

        lock.lock();
        try {
            /* Split the text the way the base class would, so each piece ends up
             * as exactly one line and keeps its identity - substring() returns
             * the same instance when it covers the whole string. */
            int offset = 0;
            while (offset < text.length()) {
                String remainder = text.substring(offset);
                int max = mPaint.breakText(remainder, true, mWidth - (mHorizontalPadding * 2), null);
                if (max <= 0) break;
                int lf = remainder.indexOf('\n');
                int take = ((lf >= 0) && (lf < max)) ? lf : max;

                String piece = remainder.substring(0, take);
                remember(piece, clip(spans, offset, offset + take));
                super.add(piece, color, false);

                offset += take;
                if ((lf >= 0) && (lf < max)) offset++; // skip the newline itself
                if (!wordWrap) break;
            }
        } finally {
            lock.unlock();
        }
    }

    private static ColorSpan[] clip(ColorSpan[] spans, int from, int to) {
        ColorSpan[] clipped = new ColorSpan[spans.length];
        int count = 0;
        for (ColorSpan span : spans) {
            ColorSpan c = span.clip(from, to);
            if (c != null) clipped[count++] = c;
        }
        if (count == 0) return null;
        ColorSpan[] result = new ColorSpan[count];
        System.arraycopy(clipped, 0, result, 0, count);
        return result;
    }

    private void remember(String piece, ColorSpan[] spans) {
        if (spans == null) return;
        mSpans.put(piece, spans);
        mSpanOrder.add(piece);
        while (mSpanOrder.size() > Math.max(mLineCount, 1) * SPAN_CACHE_FACTOR) {
            mSpans.remove(mSpanOrder.remove());
        }
    }

    @Override
    protected Bitmap getBitmap(String text, int color, int width, Justification justification, Bitmap inBitmap) {
        ColorSpan[] spans;
        lock.lock();
        try {
            spans = mSpans.get(text);
        } finally {
            lock.unlock();
        }
        if (spans == null) {
            return super.getBitmap(text, color, width, justification, inBitmap);
        }

        /* Mirrors the base class layout: left justified, same padding and
         * baseline, drawing run by run instead of in one go. */
        Rect r = new Rect();
        mPaint.getTextBounds(text, 0, text.length(), r);

        Bitmap bitmap;
        if (inBitmap == null) {
            if (width == WIDTH_AUTO) width = (r.right - r.left) + (mHorizontalPadding * 2);
            bitmap = Bitmap.createBitmap(width, mLineHeight, Bitmap.Config.ARGB_8888);
        } else {
            bitmap = inBitmap;
            bitmap.eraseColor(0x00000000);
        }

        Canvas c = new Canvas(bitmap);
        float x = -r.left + mHorizontalPadding;
        float y = -r.top + mVerticalPadding;

        int position = 0;
        for (ColorSpan span : spans) {
            if (span.start > position) {
                x = draw(c, text.substring(position, span.start), x, y, color, null);
            }
            x = draw(c, text.substring(span.start, span.end), x, y, span.color, span);
            position = span.end;
        }
        if (position < text.length()) {
            draw(c, text.substring(position), x, y, color, null);
        }

        return bitmap;
    }

    /** Draws one run and returns the x to continue at */
    private float draw(Canvas c, String text, float x, float y, int color, ColorSpan span) {
        if (text.length() == 0) return x;

        boolean bold = (span != null) && span.isBold();
        boolean reverse = (span != null) && span.isReverse();
        boolean halfBright = (span != null) && span.isHalfBright();

        if (halfBright) {
            color = Color.argb(Color.alpha(color), Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2);
        }

        mPaint.setFakeBoldText(bold);
        float width = mPaint.measureText(text);

        if (reverse) {
            /* A reversed run is a filled background with dark text on top */
            mPaint.clearShadowLayer();
            mPaint.setColor(color);
            c.drawRect(x, 0, x + width, mLineHeight, mPaint);
            mPaint.setShadowLayer(1.0f, 1.0f, 1.0f, Color.BLACK);
            mPaint.setColor(Color.BLACK);
        } else {
            mPaint.setColor(color);
        }

        c.drawText(text, x, y, mPaint);
        mPaint.setFakeBoldText(false);
        return x + width;
    }
}
