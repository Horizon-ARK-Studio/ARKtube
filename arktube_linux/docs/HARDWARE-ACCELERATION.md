# Hardware acceleration & fixing stutter

`arktube_linux` asks WebKitGTK to keep hardware acceleration on
unconditionally (`WEBKIT_HARDWARE_ACCELERATION_POLICY_ALWAYS`, set in
`src/main.c`). That controls WebKit's own *compositing* -- whether the
page is drawn via the GPU at all -- but it doesn't by itself make
video decode fast. Most of the "sometimes stuttery" YouTube playback
people see comes from one of two separate layers underneath that, and
they need different fixes.

## 1. Video decode: install the GStreamer VA-API plugins

WebKitGTK plays video through GStreamer, not through its own decoder.
Out of the box, most distros only ship GStreamer's *software* decoders
-- every frame of an H.264/VP9/AV1 stream gets decoded on the CPU,
which is exactly what produces dropped frames and stutter on anything
but a fast desktop CPU, especially at 1080p+ or on a laptop/NUC/mini-PC
sitting under a TV. Installing the VA-API plugin set lets GStreamer
hand decode off to the GPU instead:

```bash
# Debian/Ubuntu
sudo apt install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
                  gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly \
                  gstreamer1.0-libav gstreamer1.0-vaapi gstreamer1.0-gl

# Fedora
sudo dnf install gstreamer1-plugins-base gstreamer1-plugins-good \
                  gstreamer1-plugins-bad-free gstreamer1-plugins-ugly-free \
                  gstreamer1-libav gstreamer1-vaapi

# Arch / Manjaro
sudo pacman -S gst-plugins-base gst-plugins-good gst-plugins-bad \
               gst-plugins-ugly gst-libav gstreamer-vaapi
```

Without `gstreamer1.0-plugins-bad` (or its distro equivalent),
subtitles fall back to a degraded path ("WebKit wasn't able to find a
WebVTT encoder"). Without the VA-API package and `gstreamer1.0-gl` /
`gst-plugins-base`'s GL support, decode and compositing both run
entirely on the CPU -- the exact combination that shows up as stutter.

### Picking the right VA-API driver

`vainfo` (from the `libva-utils`/`libva2` package) reports which
driver got picked and what it can decode:

```bash
vainfo
```

* **Intel, Broadwell (2014) or newer** -- the modern driver is `iHD`
  (`intel-media-driver` / `intel-media-va-driver` package, not the
  older `libva-intel-driver`). Force it explicitly if the wrong one
  gets picked:

  ```bash
  export LIBVA_DRIVER_NAME=iHD
  ```

* **Intel, older than Broadwell** -- use the legacy `i965` driver
  (`libva-intel-driver` / `intel-media-va-driver-non-free`) instead.

* **AMD** -- Mesa's `radeonsi` VA-API driver, shipped as part of
  `mesa-va-drivers` (Debian/Ubuntu/Fedora) or already bundled in
  `mesa` (Arch). No separate package to hunt down on most distros.

* **NVIDIA** -- proprietary drivers don't ship a VA-API driver at all;
  `nouveau` support is limited to older GPUs. See the `nvidia-vaapi-driver`
  project if hardware decode matters more than trying the fallback
  below on NVIDIA specifically.

* **Anything gstreamer-vaapi doesn't recognize by default** -- by
  design it only auto-loads the Intel and Mesa VA drivers; everything
  else is ignored unless you set:

  ```bash
  export GST_VAAPI_ALL_DRIVERS=1
  ```

None of this is `arktube_linux`-specific configuration -- it's the
same GStreamer/VA-API stack every WebKitGTK app (GNOME Web/Epiphany
included) sits on top of, so a working `vainfo` and a correctly-set
`LIBVA_DRIVER_NAME` fixes decode-side stutter here the same way it
would for any of them.

## 2. Compositing: the DMA-BUF renderer escape hatch

Separately from decode, WebKitGTK 2.42+ shares its rendered frames
between the web-content process and the UI process via DMA-BUF
buffers by default. It's the faster, lower-copy path, but on a range
of real setups -- some NVIDIA + Wayland combinations, some KDE/KWin
sessions, some Mesa versions -- it's the thing that's actually broken,
surfacing as anything from a blank window to tearing/stutter that
looks identical to the decode-side problem above. If GStreamer/VA-API
is confirmed working (`vainfo` looks right, the packages above are
installed) and playback is still rough, try the legacy path instead:

```bash
WEBKIT_DISABLE_DMABUF_RENDERER=1 ./build/arktube_linux
```

This is deliberately **not** set by default in `src/main.c` or the
`.desktop` launcher: on setups where DMA-BUF works correctly it's the
faster of the two paths, so forcing it off unconditionally would trade
away performance for everyone to fix a problem only some setups have.
Treat it as a one-line troubleshooting step, not a permanent config
change, unless you've confirmed your setup actually needs it.

If accelerated compositing is broken outright (not just this specific
buffer-sharing path -- e.g. a blank white window rather than a
stuttery one), `WEBKIT_DISABLE_COMPOSITING_MODE=1` is the more drastic
fallback; that one does trade real GPU compositing for a stable
software path, so reach for it only after the DMA-BUF-specific
variable above didn't help.

## Summary

| Symptom | Likely cause | Fix |
|---|---|---|
| Dropped frames / stutter during playback, `vainfo` shows no usable driver | Software video decode | Install the GStreamer VA-API packages above; set `LIBVA_DRIVER_NAME` if the wrong driver gets picked |
| Stutter/tearing persists with VA-API confirmed working, especially on NVIDIA + Wayland or KDE | DMA-BUF compositing IPC path | `WEBKIT_DISABLE_DMABUF_RENDERER=1` |
| Blank/white window, or the app doesn't render at all | Accelerated compositing broken outright | `WEBKIT_DISABLE_COMPOSITING_MODE=1` (last resort -- disables GPU compositing entirely) |
