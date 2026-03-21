(ns spock.audio
  "OpenAL audio system.

   Usage:
     (audio/init!)
     (def snd (audio/load-sound! \"assets/audio/beep.wav\"))
     (audio/play! snd)
     (def src (audio/loop! snd))
     (audio/stop! src)
     (audio/set-gain! src 0.5)
     (audio/cleanup!)"
  (:require [spock.log :as log])
  (:import [java.io File]
           [java.nio ByteBuffer ByteOrder]
           [javax.sound.sampled AudioSystem]
           [org.lwjgl BufferUtils]
           [org.lwjgl.openal AL ALC AL10 ALC10]
           [org.lwjgl.stb STBVorbis]
           [org.lwjgl.system MemoryUtil]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private al-device (atom nil))  ; Long handle
(defonce ^:private al-context (atom nil)) ; Long handle
(defonce ^:private sources (atom #{}))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Open the default OpenAL device, create and activate a context."
  []
  (let [device (ALC10/alcOpenDevice ^CharSequence nil)
        _ (when (= device 0)
            (throw (RuntimeException. "OpenAL: failed to open device")))
        context (ALC10/alcCreateContext device ^org.lwjgl.system.MemoryStack nil)
        _ (when (= context 0)
            (throw (RuntimeException. "OpenAL: failed to create context")))]
    (ALC10/alcMakeContextCurrent context)
    (AL/createCapabilities (ALC/createCapabilities device))
    (reset! al-device device)
    (reset! al-context context)
    (log/info "OpenAL: init OK")))

(defn cleanup!
  "Stop all sources and destroy the OpenAL context and device."
  []
  (doseq [src @sources]
    (AL10/alSourceStop src)
    (AL10/alDeleteSources src))
  (reset! sources #{})
  (ALC10/alcMakeContextCurrent 0)
  (when-let [ctx @al-context]
    (ALC10/alcDestroyContext ctx))
  (when-let [dev @al-device]
    (ALC10/alcCloseDevice dev))
  (reset! al-context nil)
  (reset! al-device nil)
  (log/info "OpenAL: cleanup OK"))

;; ---------------------------------------------------------------------------
;; Sound loading
;; ---------------------------------------------------------------------------

(defn- load-wav
  "Load a WAV file into an OpenAL buffer. Returns buffer-id."
  [path]
  (let [stream (AudioSystem/getAudioInputStream (File. ^String path))
        fmt (.getFormat stream)
        bytes (.readAllBytes stream)
        sr (int (.getSampleRate fmt))
        chans (int (.getChannels fmt))
        bits (int (.getSampleSizeInBits fmt))
        al-fmt (cond
                 (and (= chans 1) (= bits 8))  AL10/AL_FORMAT_MONO8
                 (and (= chans 1) (= bits 16)) AL10/AL_FORMAT_MONO16
                 (and (= chans 2) (= bits 8))  AL10/AL_FORMAT_STEREO8
                 (and (= chans 2) (= bits 16)) AL10/AL_FORMAT_STEREO16
                 :else (throw (RuntimeException.
                               (str "Unsupported WAV format: " chans "ch " bits "bit"))))
        buf-id (AL10/alGenBuffers)
        bb (doto (ByteBuffer/allocateDirect (count bytes))
             (.order ByteOrder/LITTLE_ENDIAN)
             (.put bytes)
             (.flip))]
    (AL10/alBufferData buf-id al-fmt bb sr)
    buf-id))

(defn- load-ogg
  "Load an OGG/Vorbis file into an OpenAL buffer via STBVorbis. Returns buffer-id."
  [path]
  (let [ch-buf (BufferUtils/createIntBuffer 1)
        sr-buf (BufferUtils/createIntBuffer 1)
        samples (STBVorbis/stb_vorbis_decode_filename path ch-buf sr-buf)]
    (when-not samples
      (throw (RuntimeException. (str "STBVorbis: failed to decode: " path))))
    (let [chans (int (.get ch-buf 0))
          sr (int (.get sr-buf 0))
          al-fmt (if (= chans 1) AL10/AL_FORMAT_MONO16 AL10/AL_FORMAT_STEREO16)
          buf-id (AL10/alGenBuffers)]
      (AL10/alBufferData buf-id al-fmt (.rewind samples) sr)
      (MemoryUtil/memFree samples)
      buf-id)))

(defn load-sound!
  "Load a sound file (WAV or OGG) into an OpenAL buffer. Returns buffer-id."
  [path]
  (log/info "audio/load-sound!:" path)
  (if (.endsWith ^String path ".ogg")
    (load-ogg path)
    (load-wav path)))

;; ---------------------------------------------------------------------------
;; Playback
;; ---------------------------------------------------------------------------

(defn play!
  "Play sound buffer buf-id once. Returns source-id."
  [buf-id]
  (let [src (AL10/alGenSources)]
    (AL10/alSourcei src AL10/AL_BUFFER buf-id)
    (AL10/alSourcef src AL10/AL_GAIN 1.0)
    (AL10/alSourcei src AL10/AL_LOOPING AL10/AL_FALSE)
    (AL10/alSourcePlay src)
    (swap! sources conj src)
    src))

(defn loop!
  "Play sound buffer buf-id in a loop. Returns source-id."
  [buf-id]
  (let [src (AL10/alGenSources)]
    (AL10/alSourcei src AL10/AL_BUFFER buf-id)
    (AL10/alSourcef src AL10/AL_GAIN 1.0)
    (AL10/alSourcei src AL10/AL_LOOPING AL10/AL_TRUE)
    (AL10/alSourcePlay src)
    (swap! sources conj src)
    src))

(defn stop!
  "Stop a source."
  [src]
  (AL10/alSourceStop src))

(defn set-gain!
  "Set the gain (volume) of a source. 1.0 = full volume."
  [src gain]
  (AL10/alSourcef src AL10/AL_GAIN (float gain)))
