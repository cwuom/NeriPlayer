# Issue 618 embedded artwork fixtures

`issue618-silent.opus` is synthetic mono silence generated locally with FFmpeg.
It contains no copyrighted recording or artwork.

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i anullsrc=r=48000:cl=mono -t 0.1 \
  -c:a libopus -b:a 16k -map_metadata -1 \
  -fflags +bitexact -flags:a +bitexact -f opus issue618-silent.opus
```

`DownloadedCoverOfflineRenderingTest` also reuses the existing
`synthetic-free-first-8.m4a`. Each test copies its audio fixture into an
isolated directory and embeds a generated 32 x 32 PNG through TagLib at runtime.
The test writes no companion artwork and disables image caches. HTTP requests
fail inside a local interceptor, without changing the device network state.
