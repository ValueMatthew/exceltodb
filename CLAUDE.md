# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Excel to MySQL data import tool for non-technical users. Vue 3 frontend + Spring Boot 3 backend.

## Build Commands

### Backend (Spring Boot)
```bash
cd server
mvn spring-boot:run          # Run development server (port 8080)
mvn package                   # Build JAR
mvn test                      # Run tests
```

### Frontend (Vue 3 + Vite)
```bash
cd client
npm install                   # Install dependencies
npm run dev                   # Run dev server (port 3000)
npm run build                 # Production build
```

## Architecture

### Frontend (`client/`)
- Vue 3 + Element Plus + Vite + Axios
- Single-page app with component-based structure
- API calls to `http://localhost:8080/api/*`

### Backend (`server/`)
- Spring Boot 3, Java 17
- Key services:
  - `ExcelParserService` — parses .xlsx, .xls, .csv via Apache POI/OpenCSV
  - `DbService` — manages HikariCP connections to multiple databases
  - `TableMatcherService` — recommends best-matching table by column match rate (≥90%)
  - `ImportService` — executes batch inserts with UPSERT/IGNORE/truncate modes
- File uploads saved to `./uploads` directory

### Data Flow
1. User selects database (from `config.yaml`) → tests connection
2. User uploads Excel/CSV → parsed and saved to temp file
3. If Excel has multiple sheets: user selects which sheet to import (default: first sheet)
4. System previews first 100 rows (by `sheetIndex`, default 0)
4. TableMatcherService calculates best match by column match rate (≥90%). Columns with default values or `ON UPDATE` (e.g. `update_time`) are excluded from matching. Primary keys are NOT excluded.
5. If match (≥90%): user confirms the recommended table and proceeds to import settings. If no match (<90%): user is prompted to check their file or contact IT. No manual table selection or create table options.
6. ImportService runs batch INSERT (5000 rows/batch) with transaction

### Configuration
Database connections are configured in `server/src/main/resources/config.yaml`:
```yaml
databases:
  - id: prod_erp
    name: 生产ERP系统
    host: localhost
    port: 3306
    username: root
    password: your_password
    database: erp_db
```

### Key API Endpoints
| Method | Path | Description |
|--------|------|-------------|
| GET | /api/databases | List configured databases |
| POST | /api/upload | Upload Excel/CSV file |
| GET | /api/preview/{filename} | Get first 100 rows (supports `sheetIndex`, default 0) |
| GET | /api/tables/{databaseId} | List tables with column info |
| POST | /api/recommend | Get best-matching table recommendation |
| POST | /api/import | Execute data import |

### Import Modes
- **TRUNCATE**: `TRUNCATE TABLE` + INSERT
- **INCREMENTAL**: Direct INSERT

### Conflict Strategies (for tables with primary keys)
- **UPDATE**: `INSERT ... ON DUPLICATE KEY UPDATE`
- **IGNORE**: `INSERT ... IGNORE`
- **ERROR**: Plain INSERT (fails on conflict)
