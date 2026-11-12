# BANK-5 – Store customer documents in Amazon S3: deployment guide

This guide covers how to create the bucket, point the application at it, which credentials are used
inside AWS and on a developer machine, and how to confirm the integration works. Every command is
copy-pasteable, and each acceptance criterion has a check in the verification section.

BANK-2 and BANK-3 wrote uploaded documents to the application server's disk
(`banking.documents.storage-dir`). BANK-5 keeps the same HTTP API but stores one object per document
in S3, under the key layout

```
customers/{customerId}/{uuid}.{ext}
```

Each object is written with server-side encryption `AES256` (SSE-S3). Downloads are still streamed
back through the application, so no presigned URL and no temporary link is ever handed out.

Endpoints (unchanged from BANK-2 apart from the new delete):

| Method   | Path                                                        | Purpose                                                                    |
| -------- | ----------------------------------------------------------- | -------------------------------------------------------------------------- |
| `POST`   | `/customers/{customerId}/documents`                          | multipart upload: parts `file`, `documentType`, `uploadedBy`, optional `note` |
| `GET`    | `/customers/{customerId}/documents`                          | the customer's documents, newest first                                     |
| `GET`    | `/customers/{customerId}/documents/{documentId}`             | download; query parameter `downloadedBy` is required                       |
| `DELETE` | `/customers/{customerId}/documents/{documentId}`             | removes the stored object and the record, returns `204 No Content`         |

---

## 1. Create the bucket

Pick a globally unique bucket name and the region the application will run in:

```bash
export BUCKET=bank-customer-documents-prod
export REGION=eu-west-1
```

Create it. For any region other than `us-east-1` a `LocationConstraint` is required:

```bash
aws s3api create-bucket \
  --bucket "$BUCKET" \
  --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION"
```

Block all public access – the application is the only way to a document, so nothing in the bucket may
be public:

```bash
aws s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
```

Set default bucket encryption to SSE-S3 (`AES256`). Uploads also request SSE-S3 per object, so an
object written by this application is encrypted even if the bucket default is later changed:

```bash
aws s3api put-bucket-encryption \
  --bucket "$BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

Confirm both settings:

```bash
aws s3api get-public-access-block --bucket "$BUCKET"
aws s3api get-bucket-encryption --bucket "$BUCKET"
```

- [ ] All four public-access flags report `true`.
- [ ] The encryption rule reports `SSEAlgorithm: AES256`.

> BANK-2's local-storage setting `banking.documents.storage-dir` is `./uploads/documents` relative to
> the working directory and is only used when the local backend is selected (section 5). No bucket
> name, region literal, access key or secret is committed to the repository: `application.properties`
> deliberately sets none of the S3 values.

---

## 2. Credentials: IAM role in AWS, local profile on a workstation

The application configures **no** credentials in code or properties. It uses the AWS SDK's default
provider chain, so the same build runs in both places with no code change:

| Where                    | What supplies the credentials                                             |
| ------------------------ | ------------------------------------------------------------------------ |
| AWS (EC2/ECS/EKS/Lambda) | The instance or task **IAM role** attached to the compute                 |
| Developer machine        | The developer's own AWS profile, selected with `AWS_PROFILE`              |

In AWS, attach a role with a policy limited to the document prefix. The application only needs
`PutObject`, `GetObject` and `DeleteObject` on `customers/*`; it never touches bucket configuration:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "CustomerDocumentObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::<bucket>/customers/*"
    }
  ]
}
```

On a developer machine, configure a profile once (or use SSO) and export its name:

```bash
export AWS_PROFILE=bank-dev
aws sts get-caller-identity        # confirms which identity the SDK will use
```

- [ ] In AWS the application runs with an IAM role and no access key in the environment.
- [ ] On a developer machine the profile named by `AWS_PROFILE` is used.
- [ ] The only inputs the application takes are bucket, region and an optional endpoint (below) –
      there is no configuration difference between the two environments.

---

## 3. Application settings

| Property                              | Environment variable             | Required                          | Example                            |
| ------------------------------------- | -------------------------------- | --------------------------------- | ---------------------------------- |
| `banking.documents.storage`           | `BANKING_DOCUMENTS_STORAGE`      | no – defaults to `s3`             | `s3`                               |
| `banking.documents.s3.bucket`         | `BANKING_DOCUMENTS_S3_BUCKET`    | yes when the backend is `s3`      | `bank-customer-documents-prod`     |
| `banking.documents.s3.region`         | `BANKING_DOCUMENTS_S3_REGION`    | yes when the backend is `s3`      | `eu-west-1`                        |
| `banking.documents.s3.endpoint`       | `BANKING_DOCUMENTS_S3_ENDPOINT`  | no – only for LocalStack / S3-compatible services | `http://localhost:4566` |
| `banking.documents.storage-dir`       | `BANKING_DOCUMENTS_STORAGE_DIR`  | no – used only when the backend is `local` | `./uploads/documents`     |

`BANKING_DOCUMENTS_STORAGE` accepts exactly `local` or `s3`. It is deliberately **not** set in
`application.properties`: the default is `s3`, so a deployment that forgets its S3 environment fails
at startup (section 6) instead of silently writing documents to the application server's disk.

Start the application against the bucket, with the bucket and region coming from the environment:

```bash
BANKING_DOCUMENTS_STORAGE=s3 \
BANKING_DOCUMENTS_S3_BUCKET="$BUCKET" \
BANKING_DOCUMENTS_S3_REGION="$REGION" \
mvn spring-boot:run
```

In a real deployment these are the same variables in the task definition, Helm values or systemd
unit, plus whatever `AWS_PROFILE` / role the platform provides.

---

## 4. Verify the integration

Create a customer to upload against, as in the BANK-2 checklist, and note the `id` in the response.
The steps below assume the customer is `1`.

```bash
curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","phone":"1234567890","status":"ACTIVE"}'
```

### 4.1 Upload writes an object under `customers/{customerId}/` (AC1, AC3, AC6)

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user" \
  -F "note=Checked against the original"
```

Expected `201 Created`, with `"originalFileName":"sample-valid.pdf"`, `"documentType":"ID_PROOF"` and
the `id` of the new document. Then confirm the object is in the bucket and nowhere else:

```bash
aws s3 ls s3://"$BUCKET"/customers/1/
```

- [ ] Exactly one key was added, named `customers/1/<uuid>.pdf` – a system-assigned name, never the
      client's file name.
- [ ] No permanent copy was left on the application server (the application server's
      `./uploads/documents` directory does not grow).
- [ ] The original name is kept in the object metadata and the encryption is visible:

      ```bash
      aws s3api head-object --bucket "$BUCKET" --key "customers/1/<uuid>.pdf"
      ```

      Expect `"ServerSideEncryption": "AES256"` and a `original-file-name` metadata entry of
      `sample-valid.pdf`.

### 4.2 Download is unchanged for the caller (AC2)

```bash
curl -i "http://localhost:8080/customers/1/documents/1?downloadedBy=ops.user" -o /tmp/downloaded.pdf
```

- [ ] Status `200`, `Content-Disposition: attachment; filename="sample-valid.pdf"` (a non-English
      name is sent as `filename*=UTF-8''…`), and `Content-Type: application/pdf` for a `.pdf`.
- [ ] The bytes are identical to the upload:
      `cmp /tmp/downloaded.pdf docs/samples/sample-valid.pdf`.
- [ ] No `Location` header and no S3 URL appears anywhere in the response – the bytes were streamed
      by the application.

### 4.3 Delete removes both the object and the record (AC9)

```bash
curl -i -X DELETE http://localhost:8080/customers/1/documents/1

aws s3 ls s3://"$BUCKET"/customers/1/                     # the key is gone
curl -i http://localhost:8080/customers/1/documents        # the record is gone
```

- [ ] The delete returns `204 No Content`.
- [ ] The object no longer appears under `customers/1/`, so no orphaned file accumulates cost.
- [ ] The document is absent from the customer's document list, and downloading it again returns
      `404` with `"detail": "Document not found with id: 1"`.

### 4.4 A stored file that has gone missing (AC8)

Upload a document, then remove its object behind the application's back:

```bash
aws s3 rm s3://"$BUCKET"/customers/1/<uuid>.pdf

curl -i "http://localhost:8080/customers/1/documents/1?downloadedBy=ops.user"
```

- [ ] The response is `404` with `"detail": "The file for this document is no longer available.
      Please upload the document again."` – the same message BANK-3 gives.
- [ ] The application log contains a `WARN` entry naming the object key.

### 4.5 S3 unavailable or rejecting the upload (AC7)

Make S3 refuse the next upload – stop LocalStack, or remove `s3:PutObject` from the policy.

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

- [ ] The response is `500` with a ProblemDetail body whose `detail` is exactly
      `The document could not be stored. Please try again.`
- [ ] `curl -s http://localhost:8080/customers/1/documents` shows no such document, and
      `aws s3 ls s3://"$BUCKET"/customers/1/` shows no new object – nothing partial was left behind.
- [ ] Retrying the same upload after restoring S3 adds exactly one document and one object; the
      retry does not duplicate anything.

---

## 5. Run locally without AWS (AC10, AC12)

### 5.1 Local filesystem backend

Set the backend to `local` and the application behaves exactly as BANK-2 did, with no AWS access at
all:

```bash
BANKING_DOCUMENTS_STORAGE=local mvn spring-boot:run
```

Files land in `banking.documents.storage-dir` (`./uploads/documents` by default). This is the setting
for a developer machine that has no AWS profile and no network access to AWS.

- [ ] With `BANKING_DOCUMENTS_STORAGE=local` the application starts and uploads/downloads work with
      no AWS credentials configured.

### 5.2 LocalStack (S3-compatible)

Run LocalStack and provision it exactly as in section 1, against its endpoint:

```bash
docker run --rm -d --name bank-5-localstack -p 4566:4566 localstack/localstack:3.7

export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
aws --endpoint-url http://localhost:4566 s3api create-bucket --bucket bank-documents-local
aws --endpoint-url http://localhost:4566 s3api put-public-access-block \
  --bucket bank-documents-local \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
aws --endpoint-url http://localhost:4566 s3api put-bucket-encryption \
  --bucket bank-documents-local \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

Then point the application at it. Only the endpoint is different; the bucket, region and storage
settings have the same names as in AWS:

```bash
BANKING_DOCUMENTS_STORAGE=s3 \
BANKING_DOCUMENTS_S3_BUCKET=bank-documents-local \
BANKING_DOCUMENTS_S3_REGION=us-east-1 \
BANKING_DOCUMENTS_S3_ENDPOINT=http://localhost:4566 \
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
mvn spring-boot:run
```

An S3-compatible endpoint is addressed path-style, so `aws --endpoint-url …` and the application both
see `http://localhost:4566/<bucket>/<key>`.

- [ ] With `BANKING_DOCUMENTS_S3_ENDPOINT` set, an upload appears in the LocalStack bucket under
      `customers/{customerId}/`.

### 5.3 Automated tests

```bash
mvn -B test
```

The S3 integration test (`S3DocumentStorageLocalStackTest`) runs against a Testcontainers LocalStack
container and therefore needs **Docker**. It uses only dummy credentials supplied by the test itself –
no AWS account, no AWS credentials and no AWS reachability are involved. The remaining S3 tests are
Mockito unit tests and the storage-selection tests bind properties only, so they need neither Docker
nor AWS. When Docker is unavailable the LocalStack-backed test is skipped and the rest of the suite
still runs.

- [ ] `mvn -B test` passes with the S3 tests included.

---

## 6. Startup failure when the S3 settings are missing (AC11)

`banking.documents.storage` defaults to `s3`. If the backend is S3 and the bucket or region is not
supplied, the application refuses to start rather than guessing, and prints:

```
Document storage is set to S3, but it is not configured: banking.documents.s3.bucket and
banking.documents.s3.region must both be set from the environment (bucket=not set, region=not set).
Set banking.documents.storage=local to store documents on the application server's disk instead.
```

Reproduce it with no S3 environment at all:

```bash
mvn spring-boot:run
```

- [ ] The startup fails with the message above, naming `banking.documents.s3.bucket`,
      `banking.documents.s3.region` and `banking.documents.storage=local`.
- [ ] Setting the two environment variables makes the application start normally.

---

## 7. Notes and limitations

- **Documents stored under BANK-3 are not migrated.** After the switch to S3, a document whose file
  only exists on the old local disk downloads as BANK-3's "please upload the document again" `404`
  until it is re-uploaded. There is no bulk migration in this change.
- **No presigned URLs, ever.** The bucket blocks all public access and the application streams the
  bytes itself, so nothing is handed to the client that could expire – the 15-minute limit on a
  temporary link is never reached because no such link exists.
- **Deleting a document also deletes its download-audit rows**, because
  `document_downloads.document_id` is a non-null foreign key. Audit retention for deleted documents
  is not modelled.
- **Not implemented:** lifecycle rules, versioning, replication, cross-region configuration,
  per-customer SSE-KMS keys, malware scanning and a periodic orphan-object sweep.

---

## Sign-off

| # | Check                                                        | Result |
| - | ------------------------------------------------------------ | ------ |
| 1 | Upload writes an object under `customers/{customerId}/`, no permanent local copy | ☐ |
| 2 | Download returns the original name, type and identical content | ☐ |
| 3 | Bucket, region and credentials come from the environment; nothing committed | ☐ |
| 4 | IAM role in AWS, `AWS_PROFILE` locally, no code change between them | ☐ |
| 5 | Bucket blocks all public access; the application streams files, no temporary link | ☐ |
| 6 | Objects are encrypted with SSE-S3 (`AES256`)                  | ☐ |
| 7 | S3 failure shows "could not be stored, please try again", leaves no record, retry does not duplicate | ☐ |
| 8 | Missing object shows BANK-3's "please upload again" message and is logged | ☐ |
| 9 | Delete removes both the record and the stored object          | ☐ |
| 10 | `BANKING_DOCUMENTS_STORAGE=local` runs without AWS access    | ☐ |
| 11 | Missing S3 settings fail startup with a readable message      | ☐ |
| 12 | `mvn -B test` passes (Docker for the LocalStack test)         | ☐ |
| 13 | This guide lets an operator create the bucket and confirm the integration | ☐ |
