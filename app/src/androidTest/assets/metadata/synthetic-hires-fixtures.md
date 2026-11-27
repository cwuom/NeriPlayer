# Synthetic Hi-Res container diagnostics

The three `synthetic-hires-*` audio files hold one second of locally generated 96 kHz, 24-bit stereo silence. They contain no third-party audio, artwork, lyrics, account data, or credentials.

`DownloadedFlacStagingRegressionTest` creates its cover and lyric text locally and uses a private cache directory for each case.

## Generation

Generate the raw FLAC fixture from the repository root:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i anullsrc=r=96000:cl=stereo -t 1 -map_metadata -1 \
  -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 \
  -fflags +bitexact -flags:a +bitexact \
  app/src/androidTest/assets/metadata/synthetic-hires-raw.flac
```

- `synthetic-hires-mp4.m4a`: use the same command with `-strict -2 -f mp4` before the output path
- `synthetic-hires-fragmented.m4a`: additionally use `-movflags empty_moov+frag_keyframe+default_base_moof` before `-f mp4`

## Diagnostic coverage

The diagnostic class is marked `ManualInstrumentedTest`. It covers formal and pending download names for all three containers, calls the actual download tag writer, and verifies:

- Native tag and cover readback
- Channel count
- The reported audio format before and after writing
- Codec configuration
- Extracted samples and timestamps

Raw FLAC also checks the encoded frames after its metadata blocks. A failed write leaves the original file byte-for-byte intact.

## Format reporting

- Android's raw FLAC extractor may return decoded PCM (`audio/raw`)
- The MP4 fixtures have a 48 kHz `stsd` field and a 96 kHz FLAC STREAMINFO configuration; the system extractor may report either
- The format and audio data before and after writing must match exactly
- The fixture generation and an independent byte audit establish the encoded stream's 96 kHz, 24-bit, stereo configuration

These generated files are not authenticated Bilibili responses, so this class does not reproduce or resolve issue #637.
