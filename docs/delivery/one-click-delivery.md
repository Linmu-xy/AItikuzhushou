# One-click delivery package

Run this command on the source Windows computer while no user is uploading files:

```powershell
powershell -ExecutionPolicy Bypass -File D:\code\tikuzhushou\scripts\Create-DeliveryPackage.ps1 -IncludeNodeModules
```

The script creates a timestamped directory and ZIP file below `delivery`. It includes the deployed backend Jar, frontend production build, operational scripts, documentation, PostgreSQL backup, MinIO object data, manifest and SHA-256 checksums.

It intentionally excludes `.env`, API keys, passwords, Redis transient queue data and log files. Send `.env` through an encrypted USB drive or password manager only. Create and use a new DeepSeek API key on the destination server.

The delivered system includes the personal AI assistant. It uses the same `DEEPSEEK_API_KEY` and daily quota pool as OCR and question generation; assistant conversations are stored in PostgreSQL, retained for 30 days, and are deleted only by their owner or the retention job.

The web application authenticates through an HttpOnly server session (12-hour inactivity timeout). A user logs in once after deployment; ordinary browser refreshes restore the workspace without storing a password or Basic authorization value in browser storage. Occupational-standard levels also ship with server-enforced question difficulty presets: foundation 60/35/5, proficient 35/50/15, and advanced 15/50/35 for easy/medium/hard questions.

On a fresh target computer, install Java 21, Node.js 20+, PostgreSQL 17, MinIO and Redis. Extract the ZIP, place the secret `.env` file on encrypted storage, and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\Import-DeliveryPackage.ps1 `
  -PackageDirectory D:\delivery\tiku-delivery-YYYYMMDD-HHMMSS `
  -TargetDirectory D:\code\tikuzhushou `
  -SecretsPath E:\secure\tikuzhushou.env
```

The import refuses to overwrite an existing target directory or database. After import, start the services and run the health test described in `docs/windows-deployment.md`.
