# Finalized audio probe fixtures

These files contain generated silence and a generated black frame sequence, with no external media.
All have a two-second duration and keep the `moov` box after the media payload.

Generated with FFmpeg:

```sh
ffmpeg -f lavfi -i anullsrc=r=96000:cl=stereo -t 2 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 -strict experimental -f mp4 -map_metadata -1 flac-in-mp4.m4a
ffmpeg -f lavfi -i color=black:s=16x16:r=1 -t 2 -an -c:v mpeg4 -map_metadata -1 video-only.mp4
```

The FLAC fixture exercises a 24-bit, 96 kHz stereo stream in MP4 when platform parsers reject the file and TagLib reports only movie duration.
The video fixture ensures a positive movie duration cannot stand in for an audio track.

`flac-with-avc.mp4` copies that FLAC audio stream and adds generated black H.264 frames.
It tests the bounded FLAC-only fallback's rejection of mixed audio/video sample entries.

```sh
ffmpeg -i flac-in-mp4.m4a -f lavfi -i color=black:s=16x16:r=1 -map 0:a:0 -map 1:v:0 -t 2 -c:a copy -c:v libx264 -preset ultrafast -pix_fmt yuv420p -strict experimental -map_metadata -1 -f mp4 flac-with-avc.mp4
```
