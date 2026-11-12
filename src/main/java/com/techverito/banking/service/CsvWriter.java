package com.techverito.banking.service;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Minimal RFC 4180 CSV writer: the quoting rules of AC6 live here and nowhere else.
 */
public final class CsvWriter {

    /** CRLF, so the file opens correctly in Excel as well as in Google Sheets. */
    public static final String LINE_SEPARATOR = "\r\n";

    private CsvWriter() {
    }

    /**
     * Renders one CSV record, without a line separator. A field is quoted when it
     * contains a comma, a double quote, CR or LF, and embedded double quotes are
     * doubled. A {@code null} field renders as an empty field.
     */
    public static String line(List<String> fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(field(fields.get(i)));
        }
        return line.toString();
    }

    /**
     * Writes one CSV record followed by {@link #LINE_SEPARATOR}. The supplied
     * writer is flushed but never closed.
     */
    public static void writeLine(Writer out, List<String> fields) throws IOException {
        out.write(line(fields));
        out.write(LINE_SEPARATOR);
    }

    private static String field(String value) {
        if (value == null) {
            return "";
        }
        boolean mustQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0;
        if (!mustQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
