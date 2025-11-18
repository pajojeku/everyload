Simple yt-dlp server for development

Requirements
- Python 3.8+
- Install dependencies: pip install -r requirements.txt

Run

```bash
python server.py
```

This starts a Flask server on port 5000.

Endpoints
- POST /download  { "url": "https://..." }  -> starts download in background, returns {"job_id": "..."}
- GET /status/<job_id>  -> returns job status (queued, running, finished, error)
 - GET /file/<job_id> -> serves the downloaded file (first file produced by yt-dlp) so clients can stream or download it
 - GET /file/<job_id> -> serves the downloaded file (first file produced by yt-dlp) so clients can stream or download it
 - GET /log/<job_id> -> returns step-by-step log messages for the job (list) or raw log text

Notes
- The server saves downloads to `downloads/` inside the `yt_dlp_server` folder.
- This is a minimal development server. For production use, add auth, rate limits, disk quotas, and run behind a production WSGI server.

Virtual environment (recommended)
---------------------------------

This repository includes a small helper script to create a virtual environment in `.venv` and install dependencies.

Run from the `yt_dlp_server/` folder:

```bash
./setup_venv.sh
# then activate:
source .venv/bin/activate
# and run the server
python server.py
```

Note: On Linux/macOS you may need to make the script executable once:

```bash
chmod +x setup_venv.sh
```

