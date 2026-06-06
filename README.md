# AudioScope

An Android app that tests how faithfully your **speaker → air → microphone** chain reproduces
sound. It plays test tones, listens with the microphone, and for each tone finds the **loudest
frequency the mic actually heard** — then shows whether the speaker reproduced the *right* pitch and
how loud it came back.

> The result is a **round-trip** measurement: it reflects the speaker *and* the microphone together,
> plus the room. It's a relative quality check, not a lab-calibrated instrument.

---

## How it works (the core idea)

Everything is built on one operation:

> **Listen → find the single loudest frequency in a band → use its value.**

The microphone signal is run through an FFT; the strongest peak is located and refined with
**parabolic interpolation** so the reported frequency is accurate to a fraction of a bin (not just
the nearest FFT bin). Each measurement yields two numbers:

- **Detected frequency** — the pitch the mic actually heard.
- **Volume** — that peak's level, mapped onto a simple **0–100** scale (−70 dBFS → 0, 0 dBFS → 100).

A faithful speaker plays back exactly the frequency it was asked to, so **detected == emitted**.
When the speaker can't reproduce a tone (or you've muted it), there's no real signal — only noise —
and the **volume gate** drops those points so the chart stays honest instead of drawing a curve out
of the noise floor.

A small **always-on monitor** keeps listening whenever the app is open (and releases the mic the
moment a test needs it), so the input-volume bar reacts live on every tab.

---

## The three tabs

### 1. Sweep

Plays **one frequency at a time**, stepping across the audible band (log-spaced, 20 Hz → 20 kHz by
default). For each tone it settles, captures, and detects the loudest received frequency.

The chart overlays three things:

- **Perfect line** — a flat, horizontal dashed line. It marks "detected pitch == the pitch played."
- **Frequency curve** — for each tone, how far the detected pitch is off the one played, in **cents**
  (100 cents = one semitone). It sits *on* the perfect line when reproduction is correct, bends **up**
  if the detected pitch is sharp and **down** if flat. It's **green** while within a semitone and
  turns **red** when the wrong frequency comes back.
- **Volume bars** — a faint column per tone showing how loud that frequency was received (0–100).

Points quieter than the gate are simply not drawn. The stats line reads e.g. *"58/60 reproduced
on-frequency • 60/60 loud enough."*

### 2. Multi-tone

Plays **many frequencies simultaneously** (12 by default), phase-spread (Schroeder phasing) so the
combined signal doesn't clip. A single capture is analysed with one FFT.

The chart looks **identical to Sweep**, but the measurement differs: each emitted tone "owns" the
band up to the half-way point (in log space) to its neighbours, and the app finds the loudest
frequency *inside that band*. So you still get the perfect line, the cents-deviation curve, and the
volume bars — just gathered all at once instead of one tone at a time. (Because the output energy is
split across all tones, the bars sit lower than in Sweep; lower the gate accordingly.)

### 3. Live

Emits **nothing** — it just listens and shows a real-time **FFT spectrum** of whatever the mic hears
(your voice, a guitar, a tone generator). The loudest peak is marked and its frequency is shown in
the **Loudest frequency** readout. This is the "hears everything" mode; it's the quickest way to
confirm the mic is working and to watch a single played tone.

---

## Controls

### Input volume bar (all tabs)

Not a control — a live readout. It shows the **volume of the loudest frequency** the mic hears right
now, on the **0–100** scale. Watch it to gauge the room before testing.

### Noise gate — "Ignore frequencies quieter than" (all tabs)

A slider on the **same 0–100 scale** as the input-volume bar. Any received frequency quieter than
this value is **ignored**: dropped from the Sweep/Multi-tone curves, and suppressed in Live. The
workflow is: watch the input-volume bar settle on the background noise, then set the gate **just
above** it. Raise it in a noisy environment (e.g. a car); lower it in a quiet room. Adjusting it
re-filters the existing result instantly — no need to re-run. *(Default: 20.)*

### Output level (Sweep & Multi-tone)

How loud the app drives the speaker, **10 %–100 %** of full scale. *(Default: 60 %.)* Adjust before
starting a test. Higher output gives a cleaner reading above the noise; too high can distort.

### Resolution — "/oct" (Sweep only)

How many frequencies are measured per octave, **1–12**. *(Default: 6.)* More points = a finer,
slower sweep; fewer = a quick, coarse scan.

### Tones (Multi-tone only)

How many simultaneous tones are played, **3–32**. *(Default: 12.)* More tones = finer frequency
coverage in one shot, but each is quieter (the output level is shared between them).

---

## A note on the microphone

The app captures with the **UNPROCESSED** audio source where the device supports it (shown as
`mic: raw`), bypassing the automatic gain control and noise suppression that ordinary apps use. This
gives a truer measurement, but it also means absolute volume readings are **lower** than a typical
tuning/level app — that's expected, not a fault. If `UNPROCESSED` isn't available it falls back to
`VOICE_RECOGNITION` then `MIC` (shown as `mic: processed`).

---

## Everything runs in Docker — the host stays clean

No Android SDK, Gradle, or adb is installed on your machine. The entire toolchain lives in one Docker
image and is removed with a single command.

```bash
./sq.sh image      # build the toolchain image (one time, ~3 GB)
./sq.sh build      # compile app/build/outputs/apk/debug/app-debug.apk
```

### Putting it on your phone

1. Enable **Developer options → USB debugging** on the phone.
2. Plug it in over USB and accept the "Allow USB debugging?" prompt.
3. Run:

```bash
./sq.sh devices    # confirm the phone shows up
./sq.sh run        # build + install + launch + stream logs
```

In the app: grant the microphone permission, **turn the media volume up**, pick a tab, set the gate,
and press **START TEST** (or **LISTEN** on the Live tab).

## Full teardown

```bash
./sq.sh nuke       # removes the image, build cache and gradle volume — host is clean again
```

## Layout

```
Dockerfile                     toolchain image (JDK 17 + Android SDK + Gradle)
sq.sh                          build / install / run / teardown helper
app/src/main/java/eu/labrago/audioscope/
  audio/ToneSource.kt          continuous-phase tone / multi-tone generator
  audio/AudioTestEngine.kt     simultaneous AudioTrack playback + AudioRecord capture
  dsp/Dsp.kt                   Hann window, FFT, detectPeak (loudest-frequency finder), dB helpers
  model/Models.kt              params + UI state
  ScopeViewModel.kt            runs the sweep / multi-tone / live measurement + the live mic monitor
  ui/ResponseChart.kt          the log-frequency chart (perfect line, cents curve, volume bars, spectrum)
  ui/ScopeScreen.kt            Jetpack Compose screen, tabs, sliders, stats
  MainActivity.kt              permission + lifecycle wiring
```
