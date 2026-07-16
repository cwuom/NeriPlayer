# complete code
import logging
import threading
from typing import Dict, List

class DownloadManager:
    def __init__(self):
        self.downloads: Dict[str, threading.Thread] = {}
        self.lock = threading.Lock()

    def add_download(self, song_id: str, thread: threading.Thread):
        with self.lock:
            self.downloads[song_id] = thread

    def remove_download(self, song_id: str):
        with self.lock:
            if song_id in self.downloads:
                del self.downloads[song_id]

    def get_downloads(self) -> List[threading.Thread]:
        with self.lock:
            return list(self.downloads.values())