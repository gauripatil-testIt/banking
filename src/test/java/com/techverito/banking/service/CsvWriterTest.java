package com.techverito.banking.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    /** Reads the fields of one CSV record back, honouring the RFC 4180 quoting. */
    private List<String> parse(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    @Test
    void line_bareValue_isWrittenUnquoted() {
        String line = CsvWriter.line(List.of("John Doe", "ACTIVE"));

        assertThat(line).isEqualTo("John Doe,ACTIVE");
        assertThat(parse(line)).containsExactly("John Doe", "ACTIVE");
    }

    @Test
    void line_valueWithComma_isQuoted() {
        String line = CsvWriter.line(List.of("Doe, John", "ACTIVE"));

        assertThat(line).isEqualTo("\"Doe, John\",ACTIVE");
        assertThat(parse(line)).containsExactly("Doe, John", "ACTIVE");
    }

    @Test
    void line_valueWithDoubleQuote_isDoubledInsideQuotedField() {
        String line = CsvWriter.line(List.of("John \"Johnny\" Doe", "ACTIVE"));

        assertThat(line).isEqualTo("\"John \"\"Johnny\"\" Doe\",ACTIVE");
        assertThat(parse(line)).containsExactly("John \"Johnny\" Doe", "ACTIVE");
    }

    @Test
    void line_valueWithLineBreak_isQuotedWithBreakPreserved() {
        String line = CsvWriter.line(List.of("first line\nsecond line", "ACTIVE"));

        assertThat(line).isEqualTo("\"first line\nsecond line\",ACTIVE");
        assertThat(parse(line)).containsExactly("first line\nsecond line", "ACTIVE");
    }

    @Test
    void line_quotedValueWithComma_isNotSplitIntoTwoColumns() {
        String line = CsvWriter.line(List.of("Smith, Jane", "jane@example.com", "ACTIVE"));

        assertThat(parse(line)).hasSize(3)
                .containsExactly("Smith, Jane", "jane@example.com", "ACTIVE");
    }

    @Test
    void line_nullField_rendersAsEmptyField() {
        String line = CsvWriter.line(Arrays.asList("proof.pdf", null));

        assertThat(line).isEqualTo("proof.pdf,");
        assertThat(parse(line)).hasSize(2).containsExactly("proof.pdf", "");
    }

    @Test
    void writeLine_nonEnglishValue_isWrittenAsUtf8Unchanged() throws IOException {
        String value = "pán kyi (übersicht)";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer out = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);

        CsvWriter.writeLine(out, List.of(value, "ACTIVE"));
        out.flush();

        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(value + ",ACTIVE" + CsvWriter.LINE_SEPARATOR);
        assertThat(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).contains(value);
    }

    @Test
    void writeLine_appendsCrLfAndLeavesTheWriterOpen() throws IOException {
        StringWriter out = new StringWriter();

        CsvWriter.writeLine(out, List.of("a", "b"));
        CsvWriter.writeLine(out, List.of("c", "d"));

        assertThat(CsvWriter.LINE_SEPARATOR).isEqualTo("\r\n");
        assertThat(out.toString()).isEqualTo("a,b\r\nc,d\r\n");
    }
}
