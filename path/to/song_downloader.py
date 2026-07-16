# complete code
import logging
import requests
from typing import Dict

class SongDownloader:
    def __init__(self):
        self.logger = logging.getLogger(__name__)

    def download_song(self, song_id: str) -> Dict[str, str]:
        try:
            response = requests.get(f"https://api.example.com/songs/{song_id}")
            response.raise_for_status()
            return {"song": response.json()["song"], "cover": response.json()["cover"]}
        except requests.RequestException as e:
            self.logger.error(f"Error downloading song {song_id}: {e}")
            return {}