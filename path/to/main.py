# complete code
import logging
import threading
from download_manager import DownloadManager
from song_downloader import SongDownloader

logging.basicConfig(level=logging.INFO)

def download_song(song_id: str):
    song_downloader = SongDownloader()
    song_data = song_downloader.download_song(song_id)
    if song_data:
        print(f"Downloaded song {song_id} with cover {song_data['cover']}")

def main():
    download_manager = DownloadManager()
    song_id = "1439814454"
    thread = threading.Thread(target=download_song, args=(song_id,))
    download_manager.add_download(song_id, thread)
    thread.start()

if __name__ == "__main__":
    main()