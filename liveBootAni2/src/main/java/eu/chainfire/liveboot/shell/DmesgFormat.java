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

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats and colors kernel log records the way util-linux dmesg does, so
 * on-screen output matches what running dmesg in a terminal shows.
 *
 * Reference: util-linux/sys-utils/dmesg.c, print_record() / set_level_color()
 * / get_subsys_delimiter(). Verified against dmesg 2.39 output on-device.
 *
 * The colors are the bright ANSI variants rather than the muted ones most
 * terminal themes use, as they read better against the boot screen.
 */
public class DmesgFormat {
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /* Terminal colors dmesg uses (terminal-colors.d names in comments) */
    private static final int COLOR_TIME   = 0xFF00FF00; // "time", green
    private static final int COLOR_SUBSYS = 0xFFFFFF00; // "subsys", brown/yellow
    private static final int COLOR_ERR    = 0xFFFF0000; // "err", red
    private static final int COLOR_WARN   = 0xFFFFFFFF; // "warn", bold (uncolored)
    private static final int COLOR_PLAIN  = 0xFFFFFFFF;

    public static final int LEVEL_EMERG   = 0;
    public static final int LEVEL_ALERT   = 1;
    public static final int LEVEL_CRIT    = 2;
    public static final int LEVEL_ERR     = 3;
    public static final int LEVEL_WARNING = 4;

    /** A formatted line plus the spans that color it */
    public static class Record {
        public final String text;
        public final ColorSpan[] spans;

        public Record(String text, ColorSpan[] spans) {
            this.text = text;
            this.spans = spans;
        }
    }

    /**
     * Formats the timestamp exactly as dmesg does: "[%5ld.%06ld] ".
     */
    public static String formatTimestamp(long microseconds) {
        long seconds = microseconds / 1000000L;
        long fraction = microseconds % 1000000L;
        return String.format(Locale.ENGLISH, "[%5d.%06d] ", seconds, fraction);
    }

    /**
     * Undoes the escaping the kernel applies to /dev/kmsg records, where
     * unprintable characters are written as \xNN. dmesg does the same before
     * display, so a message ending in \x0a shows up as a line break rather
     * than as those four characters.
     */
    public static String unescape(String message) {
        if (message.indexOf("\\x") < 0) return message;

        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length());
        int i = 0;
        while (i < message.length()) {
            char c = message.charAt(i);
            if ((c == '\\') && (i + 3 < message.length()) && (message.charAt(i + 1) == 'x')) {
                int value = hex(message, i + 2);
                if (value >= 0) {
                    out.write(value);
                    i += 4;
                    continue;
                }
            }
            if (c < 0x80) {
                out.write(c);
                i++;
            } else {
                /* keep already decoded characters intact */
                byte[] bytes = String.valueOf(c).getBytes(UTF_8);
                out.write(bytes, 0, bytes.length);
                i++;
            }
        }
        try {
            return new String(out.toByteArray(), UTF_8);
        } catch (Exception e) {
            return message;
        }
    }

    private static int hex(String s, int at) {
        if (at + 1 >= s.length()) return -1;
        int hi = Character.digit(s.charAt(at), 16);
        int lo = Character.digit(s.charAt(at + 1), 16);
        if ((hi < 0) || (lo < 0)) return -1;
        return (hi << 4) | lo;
    }

    /**
     * Finds the end of the subsystem prefix, which dmesg colors separately.
     * It is the first ':' that is followed by a blank, and the delimiter
     * includes that blank. Note this walks past colons that are not followed
     * by a blank, so "sec-battery soc:battery: fg cycle" yields a prefix of
     * "sec-battery soc:battery: ".
     *
     * There must be something left after the delimiter: dmesg leaves a message
     * ending in ": " uncolored rather than painting the entire line.
     *
     * @return index just past the delimiter, or -1 if there is none
     */
    public static int getSubsysDelimiter(String message) {
        int from = 0;
        while (from < message.length()) {
            int colon = message.indexOf(':', from);
            if (colon < 0) return -1;
            if (colon + 1 < message.length()) {
                char next = message.charAt(colon + 1);
                if ((next == ' ') || (next == '\t')) {
                    return (colon + 2 < message.length()) ? colon + 2 : -1;
                }
                from = colon + 1;
            } else {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Builds the colored line for a single kernel log record.
     *
     * @param level       kernel log level, 0-7, or -1 when unknown
     * @param timestamp   already formatted timestamp including its trailing
     *                    space, or null to omit it
     * @param message     the message body
     * @param colors      false to produce a plain white line
     */
    public static Record format(int level, String timestamp, String message, boolean colors) {
        StringBuilder sb = new StringBuilder();
        if (timestamp != null) sb.append(timestamp);
        int messageStart = sb.length();
        sb.append(message);
        String text = sb.toString();

        if (!colors) {
            return new Record(text, null);
        }

        List<ColorSpan> spans = new ArrayList<ColorSpan>(3);

        if (timestamp != null) {
            spans.add(new ColorSpan(0, messageStart, COLOR_TIME));
        }

        int bodyStart = messageStart;
        int subsys = getSubsysDelimiter(message);
        if (subsys > 0) {
            spans.add(new ColorSpan(messageStart, messageStart + subsys, COLOR_SUBSYS));
            bodyStart = messageStart + subsys;
        }

        /* dmesg colors the body by level, and only for alert/crit/err/warning -
         * emerg, notice, info and debug are left alone. Messages containing
         * "segfault at" are dimmed red regardless of level. */
        int color = COLOR_PLAIN;
        int style = ColorSpan.STYLE_NORMAL;
        boolean colored = true;
        switch (level) {
            case LEVEL_ALERT:
                color = COLOR_ERR;
                style = ColorSpan.STYLE_REVERSE;
                break;
            case LEVEL_CRIT:
                color = COLOR_ERR;
                style = ColorSpan.STYLE_BOLD;
                break;
            case LEVEL_ERR:
                color = COLOR_ERR;
                break;
            case LEVEL_WARNING:
                color = COLOR_WARN;
                style = ColorSpan.STYLE_BOLD;
                break;
            default:
                colored = false;
                break;
        }
        if (!colored && message.contains("segfault at")) {
            color = COLOR_ERR;
            style = ColorSpan.STYLE_HALFBRIGHT;
            colored = true;
        }
        if (colored && (bodyStart < text.length())) {
            spans.add(new ColorSpan(bodyStart, text.length(), color, style));
        }

        return new Record(text, spans.toArray(new ColorSpan[spans.size()]));
    }
}
