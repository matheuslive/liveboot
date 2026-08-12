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

package eu.chainfire.liveboot.shell;

/**
 * A run of characters sharing one style, mirroring the terminal attributes
 * util-linux dmesg emits: a color, optionally bold, reversed (colored
 * background with dark text) or half-bright.
 */
public class ColorSpan {
    public static final int STYLE_NORMAL     = 0;
    public static final int STYLE_BOLD       = 1;
    public static final int STYLE_REVERSE    = 2;
    public static final int STYLE_HALFBRIGHT = 4;

    /** Index of the first character, inclusive */
    public final int start;
    /** Index of the last character, exclusive */
    public final int end;
    public final int color;
    public final int style;

    public ColorSpan(int start, int end, int color, int style) {
        this.start = start;
        this.end = end;
        this.color = color;
        this.style = style;
    }

    public ColorSpan(int start, int end, int color) {
        this(start, end, color, STYLE_NORMAL);
    }

    public boolean isBold() {
        return (style & STYLE_BOLD) != 0;
    }

    public boolean isReverse() {
        return (style & STYLE_REVERSE) != 0;
    }

    public boolean isHalfBright() {
        return (style & STYLE_HALFBRIGHT) != 0;
    }

    /**
     * Returns this span clipped to [from, to), rebased so that from becomes 0,
     * or null if the span falls entirely outside that range. Used to split
     * spans along with the text when a line is word wrapped.
     */
    public ColorSpan clip(int from, int to) {
        int s = Math.max(start, from);
        int e = Math.min(end, to);
        if (s >= e) return null;
        return new ColorSpan(s - from, e - from, color, style);
    }
}
