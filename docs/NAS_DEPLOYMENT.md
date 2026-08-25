# NAS deployment (x86_64, 8 GB)

## 1. Configure

Copy `.env.example` to `.env`, set the NAS LAN address, generate a random
`APP_SECRET_KEY`, and enter the DeepSeek API key. Do not commit `.env`.

The default Compose profile uses SQLite, reserves up to 5 GB for PaddleOCR and
1.5 GB for the bookkeeping service, and exposes only the bookkeeping port.
The OCR container is reachable only inside the Compose network.

Scheduled income and expenses are enabled by default. The server checks active
scheduled templates every 15 minutes and posts matching transactions
automatically. The desktop notification bell shows budget warnings, pending AI
entries, and scheduled transactions due in the next seven days.

## 2. Start

```sh
docker compose up -d --build
docker compose logs -f bookkeeping ocr
```

The first OCR startup downloads Chinese OCR models into the `ocr-models`
volume, so it can take several minutes. Open
`http://<APP_DOMAIN>:<APP_PORT>/`, register the single local user, and create
the initial accounts with their current balances.

## 3. Screenshot entry and privacy

On the home page, choose **上传购物截图**. The bookkeeping backend validates
the image and forwards it to the local OCR container. Only recognized text is
sent to DeepSeek. The source image is not stored as a transaction attachment or
database record; the temporary multipart upload is closed after recognition.
If required transaction fields are missing, the edit dialog stays open instead
of silently posting incomplete data.

## 4. Full backup and restore

Open **Settings → Data Management → Full Backup and Restore** to download one
ZIP containing a consistent SQLite snapshot and all locally stored attachments.
Restoring a ZIP validates its manifest, paths, expanded size, and SQLite header,
then restarts the bookkeeping container. The Compose restart policy applies the
restore automatically before the database opens.

The pre-restore database and storage directory are retained beside the active
paths with a `.pre-restore-<timestamp>` suffix. Keep `.env` separately because
API keys and the application secret are intentionally not included in the ZIP.

For an additional offline backup, copy these bind-mounted directories while the
stack is stopped:

- `data/` — SQLite database
- `storage/` — application objects
- `log/` — logs (optional for recovery)

The downloaded OCR models live in the Docker-managed `ocr-models` volume and
can be downloaded again; they are not part of financial data.
