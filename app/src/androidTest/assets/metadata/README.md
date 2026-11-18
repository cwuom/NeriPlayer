# Synthetic playback metadata fixture

`synthetic-playing-restore.flac` holds 30 seconds of generated mono silence, an original title and artist, and three independent lyric tracks. It has no artwork, third-party audio, or lyrics.

Regenerate it from the repository root with FFmpeg:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i anullsrc=r=16000:cl=mono -t 30 \
  -c:a flac -sample_fmt s16 -map_metadata -1 \
  -metadata title='Original title' \
  -metadata artist='Original artist' \
  -metadata album='Synthetic restore fixture' \
  -metadata 'LYRICS=[00:01.00]original lyric' \
  -metadata 'NERI_LYRICS_ORIGINAL=[00:01.00]original lyric' \
  -metadata 'LYRICS_TRANSLATED=[00:01.00]original translation' \
  -metadata 'NERI_LYRICS_ROMANIZED=[00:01.00]original romanization' \
  app/src/androidTest/assets/metadata/synthetic-playing-restore.flac
```

`RestoredLocalPlaybackThreadTest.singleRestoreWhilePlayingPublishesOriginalMetadataToSessionAndNotification` runs on an API 37 emulator.

Preconditions:

- Notifications are allowed
- No playback, playback service, sleep timer, or sync target exists

Behavior:

- The test uses a private copy of the fixture
- It restores the saved queue and runtime preferences afterward
- It removes only its own synthetic history and statistics entries and temporary media files
