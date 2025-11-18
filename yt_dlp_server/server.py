from flask import Flask, request, jsonify, send_from_directory, abort
import uuid
import threading
import os
import time
import glob
import logging
import re

# use yt_dlp Python API for progress hooks
try:
    import yt_dlp
except Exception:
    yt_dlp = None

DOWNLOAD_DIR = os.path.join(os.path.dirname(__file__), 'downloads')
os.makedirs(DOWNLOAD_DIR, exist_ok=True)
LOG_DIR = os.path.join(os.path.dirname(__file__), 'logs')
os.makedirs(LOG_DIR, exist_ok=True)

# basic logging to console
logging.basicConfig(level=logging.INFO)

jobs = {}


def sanitize_filename(filename):
    """
    Sanitize filename: replace spaces with underscores, remove emojis and special characters.
    Keep only alphanumeric, dots, dashes, and underscores.
    """
    # Replace spaces with underscores
    name = filename.replace(' ', '_')
    
    # Remove emojis and special characters (keep only a-z, A-Z, 0-9, ., -, _)
    name = re.sub(r'[^a-zA-Z0-9._-]', '_', name)
    
    # Replace multiple consecutive underscores with single underscore
    name = re.sub(r'_{2,}', '_', name)
    
    # Trim underscores from start and end
    name = name.strip('_')
    
    # Fallback to timestamp if empty
    if not name:
        name = f'downloaded_file_{int(time.time())}'
    
    return name


def run_download(job_id, url):
    jobs[job_id] = {'status': 'running', 'url': url, 'log': []}
    log_path = os.path.join(LOG_DIR, f"{job_id}.log")

    def append_log(line: str):
        ts = time.strftime('%Y-%m-%d %H:%M:%S')
        entry = f"[{ts}] {line}"
        jobs[job_id].setdefault('log', []).append(entry)
        try:
            with open(log_path, 'a', encoding='utf-8') as f:
                f.write(entry + "\n")
        except Exception:
            pass
        logging.info(entry)

    if yt_dlp is None:
        append_log('yt_dlp module not available; ensure yt-dlp is installed in environment')
        jobs[job_id]['status'] = 'error'
        jobs[job_id]['error'] = 'yt_dlp not installed'
        return

    # progress hook called by yt_dlp
    def progress_hook(d):
        status = d.get('status')
        if status == 'downloading':
            percent = None
            total = d.get('total_bytes') or d.get('total_bytes_estimate')
            downloaded = d.get('downloaded_bytes')
            if total and downloaded:
                try:
                    percent = downloaded / total * 100
                except Exception:
                    percent = None
            speed = d.get('speed')
            entry = f"downloading: {d.get('filename', '')} {('%.1f%%' % percent) if percent else ''} downloaded={downloaded} speed={speed}"
            append_log(entry)
            jobs[job_id]['status'] = 'downloading'
            jobs[job_id]['progress'] = {'downloaded': downloaded, 'total': total, 'speed': speed}
        elif status == 'finished':
            entry = f"finished: {d.get('filename', '')}"
            append_log(entry)
            jobs[job_id]['status'] = 'finished'
            # record filename
            jobs[job_id].setdefault('files', []).append(os.path.basename(d.get('filename')))
        elif status == 'error':
            entry = f"error: {d.get('filename', '')}"
            append_log(entry)
            jobs[job_id]['status'] = 'error'

    ydl_opts = {
        'outtmpl': os.path.join(DOWNLOAD_DIR, '%(title)s.%(ext)s'),
        'progress_hooks': [progress_hook],
        'quiet': True,
        'no_warnings': True,
    }

    try:
        append_log(f"Starting download for URL: {url}")
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            
            # After download, rename files to sanitized versions
            downloaded_files = []
            
            # try to compute produced filename(s) using ydl internals
            try:
                produced = []
                # prepare_filename works for single entries
                fn = ydl.prepare_filename(info)
                if fn:
                    produced.append(fn)
                # for playlists or multiple entries, check entries
                entries = info.get('entries') if isinstance(info, dict) else None
                if entries:
                    for ent in entries:
                        try:
                            fn2 = ydl.prepare_filename(ent)
                            produced.append(fn2)
                        except Exception:
                            continue
                
                # Rename files to sanitized versions
                for filepath in produced:
                    if os.path.exists(filepath):
                        dirname = os.path.dirname(filepath)
                        basename = os.path.basename(filepath)
                        name, ext = os.path.splitext(basename)
                        sanitized_name = sanitize_filename(name)
                        sanitized_basename = sanitized_name + ext
                        new_filepath = os.path.join(dirname, sanitized_basename)
                        
                        # Rename file if name changed
                        if filepath != new_filepath:
                            try:
                                os.rename(filepath, new_filepath)
                                append_log(f"Renamed: {basename} -> {sanitized_basename}")
                                downloaded_files.append(sanitized_basename)
                            except Exception as e:
                                append_log(f"Error renaming {basename}: {e}")
                                downloaded_files.append(basename)
                        else:
                            downloaded_files.append(basename)
                    else:
                        append_log(f"File not found after download: {filepath}")
                
                if downloaded_files:
                    jobs[job_id]['files'] = downloaded_files
            except Exception as e:
                append_log(f"Could not process filenames: {e}")
        append_log('yt_dlp finished extraction')
        jobs[job_id]['status'] = 'finished'
        jobs[job_id]['output'] = 'finished'
        # try to detect files produced (if not already set by hooks or prepare_filename)
        if not jobs[job_id].get('files'):
            created = []
            for path in glob.glob(os.path.join(DOWNLOAD_DIR, '*')):
                try:
                    m = os.path.getmtime(path)
                    # if file modified within last 120 seconds, consider it
                    if m >= time.time() - 120:
                        created.append(os.path.basename(path))
                except Exception:
                    continue
            if created:
                jobs[job_id]['files'] = created
        append_log(f"Job {job_id} finished, files: {jobs[job_id].get('files')}")
    except Exception as e:
        append_log(f"Exception during yt_dlp: {e}")
        jobs[job_id]['status'] = 'error'
        jobs[job_id]['error'] = str(e)


app = Flask(__name__)


@app.route('/download', methods=['POST'])
def download():
    data = request.get_json(force=True)
    url = data.get('url')
    if not url:
        return jsonify({'error': 'missing url'}), 400
    job_id = str(uuid.uuid4())
    jobs[job_id] = {'status': 'queued', 'url': url}
    t = threading.Thread(target=run_download, args=(job_id, url), daemon=True)
    t.start()
    return jsonify({'job_id': job_id}), 202


@app.route('/status/<job_id>', methods=['GET'])
def status(job_id):
    job = jobs.get(job_id)
    if not job:
        return jsonify({'error': 'not found'}), 404
    return jsonify(job)


@app.route('/file/<job_id>', methods=['GET'])
def serve_file(job_id):
    job = jobs.get(job_id)
    if not job:
        return jsonify({'error': 'not found'}), 404
    files = job.get('files')
    if not files:
        return jsonify({'error': 'no file for job'}), 404
    # serve the first file
    filename = files[0]
    path = os.path.join(DOWNLOAD_DIR, filename)
    if not os.path.exists(path):
        return jsonify({'error': 'file missing'}), 404
    logging.info(f"HTTP GET /file/{job_id} -> serving {filename}")
    # Use send_from_directory so client can stream the file
    return send_from_directory(DOWNLOAD_DIR, filename)


@app.route('/log/<job_id>', methods=['GET'])
def get_log(job_id):
    job = jobs.get(job_id)
    if not job:
        return jsonify({'error': 'not found'}), 404
    # prefer returning structured log if present
    log = job.get('log')
    if log:
        return jsonify({'job_id': job_id, 'log': log})
    # fallback: try to serve log file
    log_path = os.path.join(LOG_DIR, f"{job_id}.log")
    if os.path.exists(log_path):
        try:
            with open(log_path, 'r', encoding='utf-8') as f:
                content = f.read()
            return jsonify({'job_id': job_id, 'log_text': content})
        except Exception:
            return jsonify({'error': 'cannot read log file'}), 500
    return jsonify({'error': 'no log available'}), 404


if __name__ == '__main__':
    # For development only. In production use gunicorn/uvicorn etc.
    app.run(host='0.0.0.0', port=5000)
