#!/usr/bin/env bash
#
# BANK-2 manual test fixtures.
#
# Creates the sample files used by docs/BANK-2-document-upload-manual-test.md:
#
#   sample-valid.pdf        a valid single page PDF
#   sample-valid.png        a valid PNG image
#   sample-oversized.pdf    11 MiB, i.e. over the configured 10 MB limit
#   sample-wrong-type.txt   a file type the upload endpoint must reject
#
# Usage, from anywhere:
#
#   bash docs/samples/create-sample-documents.sh
#
# The files are written next to this script. No binary sample is committed to the
# repository: every one of them is generated here.
set -eu

samples_dir=$(cd "$(dirname "$0")" && pwd)
cd "$samples_dir"

rm -f sample-valid.pdf sample-valid.png sample-oversized.pdf sample-wrong-type.txt

# ---------------------------------------------------------------------------
# 1. sample-valid.pdf - a minimal but well formed single page PDF.
#
# Built line by line so that the cross reference offsets are the real byte
# offsets: everything written here is ASCII, so the byte count from `wc -c` is
# the offset of the next object, and the content stream /Length is the real
# length of the stream data.
# ---------------------------------------------------------------------------
pdf=sample-valid.pdf
: > "$pdf"

line() { printf '%s\n' "$*" >> "$pdf"; }
offset() { wc -c < "$pdf" | tr -d '[:space:]'; }

line '%PDF-1.4'

off_catalog=$(offset)
line '1 0 obj'
line '<< /Type /Catalog /Pages 2 0 R >>'
line 'endobj'

off_pages=$(offset)
line '2 0 obj'
line '<< /Type /Pages /Kids [3 0 R] /Count 1 >>'
line 'endobj'

off_page=$(offset)
line '3 0 obj'
line '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200]'
line '   /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'
line 'endobj'

off_contents=$(offset)
content='BT /F1 18 Tf 20 100 Td (Sample KYC document) Tj ET'
line '4 0 obj'
line "<< /Length ${#content} >>"
line 'stream'
line "$content"
line 'endstream'
line 'endobj'

off_font=$(offset)
line '5 0 obj'
line '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
line 'endobj'

off_xref=$(offset)
line 'xref'
line '0 6'
line '0000000000 65535 f '
printf '%010d 00000 n \n' "$off_catalog" >> "$pdf"
printf '%010d 00000 n \n' "$off_pages" >> "$pdf"
printf '%010d 00000 n \n' "$off_page" >> "$pdf"
printf '%010d 00000 n \n' "$off_contents" >> "$pdf"
printf '%010d 00000 n \n' "$off_font" >> "$pdf"
line 'trailer'
line '<< /Size 6 /Root 1 0 R >>'
line 'startxref'
line "$off_xref"
line '%%EOF'

# ---------------------------------------------------------------------------
# 2. sample-valid.png - a real PNG, encoded with the JDK's ImageIO.
#
# Generated rather than committed as a base64 blob so the file is guaranteed to
# be a valid image. A JDK is already required to build and run this application.
# ---------------------------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
    echo "error: 'java' is not on PATH; a JDK is required to generate sample-valid.png." >&2
    exit 1
fi

png_tmp=$(mktemp -d)
trap 'rm -rf "$png_tmp"' EXIT

cat > "$png_tmp/MakeSamplePng.java" <<'JAVA'
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Writes the small valid PNG used as the "valid image" sample for BANK-2. */
public final class MakeSamplePng {

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(240, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(new Color(0x1F4E79));
        graphics.fillRect(10, 10, image.getWidth() - 20, image.getHeight() - 20);
        graphics.dispose();
        ImageIO.write(image, "png", new File(args[0]));
    }
}
JAVA

java "$png_tmp/MakeSamplePng.java" "$samples_dir/sample-valid.png"

trap - EXIT
rm -rf "$png_tmp"

# ---------------------------------------------------------------------------
# 3. sample-oversized.pdf - 11 MiB, one megabyte over the 10 MB default limit.
#
# It starts with a PDF header (so it also looks like a PDF to a human) and is
# then padded past the limit, which is what the upload endpoint must reject.
# ---------------------------------------------------------------------------
printf '%%PDF-1.4\n%% oversized sample for BANK-2 (see docs/BANK-2-document-upload-manual-test.md)\n' \
    > sample-oversized.pdf
dd if=/dev/zero bs=1048576 count=11 >> sample-oversized.pdf 2>/dev/null

# ---------------------------------------------------------------------------
# 4. sample-wrong-type.txt - a small file whose type is outside the configured
#    allowed extensions, so the upload endpoint must reject it without storing
#    anything.
# ---------------------------------------------------------------------------
cat > sample-wrong-type.txt <<'TXT'
BANK-2 sample file.

This is a plain text file. The upload endpoint accepts only the extensions listed
in banking.documents.allowed-extensions (pdf, jpg and png by default), so this
file must be rejected and nothing may be saved.
TXT

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
echo "Sample files created in $samples_dir:"
for sample in sample-valid.pdf sample-valid.png sample-oversized.pdf sample-wrong-type.txt; do
    printf '  %-24s %s bytes\n' "$sample" "$(wc -c < "$sample" | tr -d '[:space:]')"
done
echo
echo "Next: follow docs/BANK-2-document-upload-manual-test.md"
