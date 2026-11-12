# BANK-2 – Upload a customer's documents: manual test checklist

Twelve checks, about ten minutes. Each check names the endpoint, the exact command, the expected HTTP
status and the expected body, so the behaviour can be confirmed without reading code.

Automated coverage for the same ground lives in `DocumentServiceTest`, `DocumentControllerTest`,
`LocalFileSystemDocumentStorageTest`, `DocumentPropertiesTest` and `GlobalExceptionHandlerTest`. This
checklist is what a reviewer runs against the real HTTP endpoints and the real filesystem.

Endpoints under test:

| Method | Path                                | Purpose                                                                       |
| ------ | ----------------------------------- | ----------------------------------------------------------------------------- |
| `POST` | `/customers/{customerId}/documents` | multipart upload: parts `file`, `documentType`, `uploadedBy` and optional `note` |
| `GET`  | `/customers/{customerId}/documents` | the customer's documents, newest first                                        |

`documentType` is one of `ID_PROOF`, `ADDRESS_PROOF`, `SIGNED_FORM`. `uploadedBy` is required and is
the value recorded as the uploader (this story has no authentication, so the form field is the
uploader).

## Before you start

1. **Run the automated build** (AC11). From the repository root:

   ```bash
   mvn -B test
   ```

   Expect `BUILD SUCCESS`, including the pre-existing `CustomerServiceTest`, `AccountServiceTest`,
   `CustomerControllerTest` and `AccountControllerTest` which must be unchanged.

2. **Create the sample files** (AC10):

   ```bash
   bash docs/samples/create-sample-documents.sh
   ```

   The script writes the files next to itself, under `docs/samples/`, and prints their names and
   sizes:

   | File                     | What it is                                                        |
   | ------------------------ | ----------------------------------------------------------------- |
   | `sample-valid.pdf`       | a valid single page PDF – the "good" upload                       |
   | `sample-valid.png`       | a valid PNG image – the "good" image upload                       |
   | `sample-oversized.pdf`   | 11 MiB, over the configured 10 MB limit                            |
   | `sample-wrong-type.txt`  | a `.txt` file, outside the configured allowed extensions          |

   Any real PDF, JPG or PNG from your machine can be substituted for the two valid samples.

2. **Run the automated build** (AC11). From the repository root:

   bash
   mvn -B test
   

   Expect `BUILD SUCCESS`, including the pre-existing `CustomerServiceTest`, `AccountServiceTest`,
   `CustomerControllerTest` and `AccountControllerTest` which must be unchanged.

2. **Run the automated build** (AC11). From the repository root:

   bash
   mvn -B test
   

   Expect `BUILD SUCCESS`, including the pre-existing `CustomerServiceTest`, `AccountServiceTest`,
   `CustomerControllerTest` and `AccountControllerTest` which must be unchanged.

3. **Start the application**:

   ```bash
   mvn spring-boot:run
   ```

   Files are written to `banking.documents.storage-dir`, which defaults to `./uploads/documents`
   (relative to the working directory) and is created automatically. `uploads/` is git-ignored.

4. **Create a customer to upload against** and note the `id` in the response. The checklist below
   assumes the new customer is `1`.

   ```bash
   curl -i -X POST http://localhost:8080/customers \
     -H 'Content-Type: application/json' \
     -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","phone":"1234567890","status":"ACTIVE"}'
   ```

   Expect `201 Created` and a body containing `"id":1`.

All commands below are run from the repository root.

---

## 1. Valid PDF upload (AC1, AC5, AC8)

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user" \
  -F "note=Checked against the original"
```

Expected: `201 Created` and a body like

```json
{
  "id": 1,
  "customerId": 1,
  "originalFileName": "sample-valid.pdf",
  "documentType": "ID_PROOF",
  "sizeBytes": 650,
  "uploadedAt": "2025-01-01T10:15:30.123456Z",
  "uploadedBy": "ops.user",
  "note": "Checked against the original"
}
```

- [ ] `id` is present – that is the document reference.
- [ ] `originalFileName` is exactly the name that was sent.
- [ ] `documentType`, `sizeBytes`, `uploadedAt` and `uploadedBy` are all present and non-null, and
      `uploadedBy` equals the `uploadedBy` part that was sent.
- [ ] `sizeBytes` equals the size of the file that was uploaded
      (`wc -c < docs/samples/sample-valid.pdf`).
- [ ] A file with a system-assigned name now exists (a UUID plus the extension – never the client
      name):

      ```bash
      ls -l uploads/documents
      ```

## 2. Valid image upload (AC1, AC2)

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.png" \
  -F "documentType=ADDRESS_PROOF" \
  -F "uploadedBy=ops.user"
```

Expected: `201 Created`, `"documentType":"ADDRESS_PROOF"`, `"originalFileName":"sample-valid.png"`,
and `"note":null` (the note is optional).

- [ ] The upload succeeded and a second file appeared under `uploads/documents`.

## 3. Names with spaces, brackets and non-English characters (AC6)

```bash
cp docs/samples/sample-valid.png "docs/samples/pán kyc (final) [übersicht].png"

curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/pán kyc (final) [übersicht].png" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

Expected: `201 Created`, with `"originalFileName":"pán kyc (final) [übersicht].png"`.

- [ ] The original name is kept verbatim for display.
- [ ] The name on disk is a generated UUID plus `.png`: only ASCII, no spaces, no brackets, no
      non-English characters:

      ```bash
      ls uploads/documents
      ```

## 4. Two uploads with the same file name (AC7)

Upload the same file name a second time:

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=SIGNED_FORM" \
  -F "uploadedBy=ops.user"
```

Expected: a second `201 Created` with a different `id`.

- [ ] Both documents are listed (see check 10) with the same `originalFileName` and different `id`s.
- [ ] `uploads/documents` contains a second, different file – the first upload was not replaced.

## 5. Unsupported file type (AC2)

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-wrong-type.txt" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

Expected: `400 Bad Request` and a ProblemDetail body whose `detail` states the rule:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "The file type is not allowed. Only PDF, JPG and PNG files are allowed, up to 10 MB."
}
```

- [ ] The message names the accepted types.
- [ ] Nothing was saved: the document does not appear in check 10 and no new file appeared in
      `uploads/documents`.

## 6. Oversized file (AC2)

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-oversized.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

Expected: `400 Bad Request` in the ProblemDetail shape, with a `detail` that states the 10 MB limit –
either

```
The uploaded file is too large. The maximum allowed size is 10 MB.
```

when the servlet container's `spring.servlet.multipart.max-file-size` rejects the part, or

```
The file is too large. Only PDF, JPG and PNG files are allowed, up to 10 MB.
```

when the request reaches the service first.

- [ ] The status is `400` and the message states the 10 MB limit.
- [ ] Nothing was saved. If `curl` reports the connection was closed before the response body was
      read, confirm via check 10 and `ls uploads/documents` that no document and no file were added –
      an over-limit part is aborted by the container.

## 7. Empty file (AC3)

```bash
: > /tmp/empty.pdf

curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@/tmp/empty.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

Expected: `400 Bad Request`, `"detail": "The file is empty. Please choose a file to upload."`

- [ ] The message says the file is empty.
- [ ] Nothing was saved.

## 8. Unknown customer (AC4)

```bash
curl -i -X POST http://localhost:8080/customers/9999/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

Expected: `404 Not Found` and

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Customer not found with id: 9999"
}
```

- [ ] The status is `404` and no file was written to `uploads/documents`.

## 9. Missing uploader (AC1, AC9)

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF"
```

Expected: `400 Bad Request` in the same ProblemDetail shape (a `type`/`title`/`status`/`detail`
body, not an empty response), with a `detail` naming `uploadedBy`.

- [ ] The status is `400` and the error body is a ProblemDetail.
- [ ] Nothing was saved.

## 10. The documents appear on the customer's record, newest first (AC5)

```bash
curl -s http://localhost:8080/customers/1/documents
```

Expected: `200 OK` (AC9's error format does not apply here) and a JSON array of every document
uploaded for customer `1` in the checks above, with the most recently uploaded one first.

- [ ] The array contains the documents from checks 1, 2, 3 and 4 – and none of the rejected uploads
      from checks 5 to 9.
- [ ] The order is newest first: the two `sample-valid.pdf` entries from check 4 are at the top,
      most recent first, and the check 1 upload is near the bottom.
- [ ] An unknown customer gives the same `404` ProblemDetail as check 8:

      ```bash
      curl -i http://localhost:8080/customers/9999/documents
      ```

## 11. Nothing partial is left behind (AC9)

```bash
ls uploads/documents | wc -l          # before
# ... run any rejected upload (checks 5, 6, 7, 8 or 9) ...
ls uploads/documents | wc -l          # after
curl -s http://localhost:8080/customers/1/documents | grep -c '"id"'   # unchanged
```

- [ ] A rejected upload adds neither a stored file nor a database record.
- [ ] Every error body seen above has the same `type`/`title`/`status`/`detail` shape as the rest of
      the application, including the existing `/customers` and `/accounts` errors.

## 12. The rules come from configuration, not code (AC12)

Restart the application with the rules overridden – no rebuild, no code change:

```bash
BANKING_DOCUMENTS_ALLOWED_EXTENSIONS=pdf,jpg,png,txt \
BANKING_DOCUMENTS_MAX_FILE_SIZE=1MB \
mvn spring-boot:run
```

- [ ] The `.txt` upload from check 5 is now accepted (`201 Created`), because `txt` is in the
      configured list.
- [ ] A 2 MB PDF is now rejected with `"detail": "The file is too large. Only PDF, JPG, PNG and TXT
      files are allowed, up to 1 MB."` (the message is built from the configuration). Create the
      2 MB file with:

      ```bash
      cp docs/samples/sample-valid.pdf /tmp/sample-2mb.pdf
      dd if=/dev/zero bs=1048576 count=2 >> /tmp/sample-2mb.pdf 2>/dev/null
      curl -i -X POST http://localhost:8080/customers/1/documents \
        -F "file=@/tmp/sample-2mb.pdf" \
        -F "documentType=ID_PROOF" \
        -F "uploadedBy=ops.user"
      ```

- [ ] `BANKING_DOCUMENTS_STORAGE_DIR=/tmp/bank-2-documents` makes the next upload be written to
      `/tmp/bank-2-documents` instead of `./uploads/documents`.

## Sign-off

| # | Check                                        | Result |
| - | -------------------------------------------- | ------ |
| 1 | Valid PDF upload, all response fields        | ☐      |
| 2 | Valid image upload                           | ☐      |
| 3 | Spaces, brackets and non-English file name   | ☐      |
| 4 | Two uploads with the same file name          | ☐      |
| 5 | Unsupported type rejected, message states rule | ☐    |
| 6 | Oversized file rejected, message states 10 MB | ☐     |
| 7 | Empty file rejected, message says empty      | ☐      |
| 8 | Unknown customer rejected, 404, nothing stored | ☐    |
| 9 | Missing uploadedBy rejected, ProblemDetail    | ☐      |
| 10 | Listing is newest first                     | ☐      |
| 11 | No partial record, consistent error format   | ☐      |
| 12 | Types and size limit come from configuration | ☐      |
| 13 | `mvn -B test` passes                         | ☐      |
