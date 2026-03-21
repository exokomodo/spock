#!/usr/bin/env bash
# gen_sounds.sh — Generate placeholder audio assets for spin-shooter.
#
# Tries ffmpeg first, then sox, then falls back to Python (stdlib only)
# to write silent WAV files so the build never requires external tools.
#
# Outputs (all 44100 Hz, 16-bit, mono):
#   shoot.wav    — 200 ms sine blip at 880 Hz
#   hit.wav      — 100 ms white-noise burst
#   gameover.wav — 600 ms descending tone (880 → 220 Hz)

set -euo pipefail
cd "$(dirname "$0")"

have() { command -v "$1" &>/dev/null; }

echo "Generating spin-shooter audio assets..."

if have ffmpeg; then
  echo "  Using ffmpeg"

  # shoot.wav — 200 ms 880 Hz sine
  ffmpeg -y -loglevel error \
    -f lavfi -i "sine=frequency=880:duration=0.2" \
    -ar 44100 -ac 1 -sample_fmt s16 shoot.wav

  # hit.wav — 100 ms white noise
  ffmpeg -y -loglevel error \
    -f lavfi -i "anoisesrc=color=white:duration=0.1:amplitude=0.5" \
    -ar 44100 -ac 1 -sample_fmt s16 hit.wav

  # gameover.wav — 600 ms descending tone: stacked notes at 880, 660, 440, 220 Hz
  ffmpeg -y -loglevel error \
    -f lavfi -i "sine=frequency=880:duration=0.15" \
    -f lavfi -i "sine=frequency=660:duration=0.15" \
    -f lavfi -i "sine=frequency=440:duration=0.15" \
    -f lavfi -i "sine=frequency=220:duration=0.15" \
    -filter_complex "[0][1][2][3]concat=n=4:v=0:a=1[out]" -map "[out]" \
    -ar 44100 -ac 1 -sample_fmt s16 gameover.wav

elif have sox; then
  echo "  Using sox"

  # shoot.wav — 200 ms 880 Hz sine
  sox -n -r 44100 -b 16 -c 1 shoot.wav synth 0.2 sine 880

  # hit.wav — 100 ms white noise
  sox -n -r 44100 -b 16 -c 1 hit.wav synth 0.1 brownnoise

  # gameover.wav — descending: 880 → 220 Hz over 600 ms
  sox -n -r 44100 -b 16 -c 1 gameover.wav synth 0.6 sine 880:220

else
  echo "  Neither ffmpeg nor sox found — writing silent placeholder WAVs via Python"
  python3 - <<'PYEOF'
import struct, math, os

def write_wav(path, duration_s, sample_rate=44100):
    """Write a minimal silent PCM WAV file."""
    n_samples = int(sample_rate * duration_s)
    data = bytes(n_samples * 2)          # 16-bit zeros = silence

    with open(path, 'wb') as f:
        # RIFF header
        data_size   = len(data)
        chunk_size  = 36 + data_size
        f.write(b'RIFF')
        f.write(struct.pack('<I', chunk_size))
        f.write(b'WAVE')
        # fmt  sub-chunk
        f.write(b'fmt ')
        f.write(struct.pack('<I', 16))           # sub-chunk size
        f.write(struct.pack('<H', 1))            # PCM
        f.write(struct.pack('<H', 1))            # channels (mono)
        f.write(struct.pack('<I', sample_rate))  # sample rate
        f.write(struct.pack('<I', sample_rate * 2))  # byte rate
        f.write(struct.pack('<H', 2))            # block align
        f.write(struct.pack('<H', 16))           # bits per sample
        # data sub-chunk
        f.write(b'data')
        f.write(struct.pack('<I', data_size))
        f.write(data)
    print(f'  wrote {path} ({n_samples} samples, {duration_s}s silence)')

os.chdir(os.path.dirname(os.path.abspath(__file__)))
write_wav('shoot.wav',    0.2)
write_wav('hit.wav',      0.1)
write_wav('gameover.wav', 0.6)
PYEOF
fi

echo "Done."
ls -lh shoot.wav hit.wav gameover.wav
