(ns spock.audio.core
  "OpenAL audio subsystem.

   Lifecycle:
     (init!)                — open default device, create + make-current context
     (load-sound! path)     — load a WAV file, return sound-id (long)
     (play! sound-id)       — fire-and-forget one-shot playback
     (loop! sound-id)       — start looping source; return source-id (long)
     (stop! source-id)      — stop and delete a source
     (set-gain! src g)      — set gain on a source (0.0–1.0)
     (set-master-volume! v) — set listener AL_GAIN (master volume)
     (tick!)                — clean up finished one-shot sources (call each frame)
     (cleanup!)             — tear everything down"
  (:require [spock.log :as log])
  (:import [org.lwjgl.openal AL ALC ALC10 AL10]
           [javax.sound.sampled AudioSystem AudioFormat AudioFormat$Encoding]
           [org.lwjgl.system MemoryUtil]
           [java.nio ByteBuffer ByteOrder]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Internal state
;; ---------------------------------------------------------------------------

;; Device and context handles (long)
(def ^:private ^:volatile-mutable -device  (long 0))
(def ^:private ^:volatile-mutable -context (long 0))

;; Set of loaded buffer ids (ints stored as longs)
(def ^:private buffers (atom #{}))

;; Map of source-id → :one-shot | :loop
(def ^:private sources (atom {}))

;; ---------------------------------------------------------------------------
;; init!
;; ---------------------------------------------------------------------------

(defn init!
  "Open the default OpenAL device, create a context, and make it current."
  []
  (let [dev (ALC10/alcOpenDevice ^String nil)]
    (when (= dev MemoryUtil/NULL)
      (throw (RuntimeException. "OpenAL: failed to open default device")))
    (let [alc-caps (ALC/createCapabilities dev)
          ctx      (ALC10/alcCreateContext dev ^java.nio.IntBuffer nil)]
      (when (= ctx MemoryUtil/NULL)
        (ALC10/alcCloseDevice dev)
        (throw (RuntimeException. "OpenAL: failed to create context")))
      (ALC10/alcMakeContextCurrent ctx)
      (AL/createCapabilities alc-caps)
      (set! -device  dev)
      (set! -context ctx)
      (log/info "audio/init! device=" dev "context=" ctx))))

;; ---------------------------------------------------------------------------
;; cleanup!
;; ---------------------------------------------------------------------------

(defn cleanup!
  "Stop all sources, delete all buffers, destroy context and device."
  []
  (log/info "audio/cleanup!")
  ;; Stop + delete all sources
  (doseq [src (keys @sources)]
    (try
      (AL10/alSourceStop (int src))
      (AL10/alDeleteSources (int src))
      (catch Exception e
        (log/warn "audio/cleanup! source error:" (.getMessage e)))))
  (reset! sources {})
  ;; Delete all buffers
  (doseq [buf @buffers]
    (try
      (AL10/alDeleteBuffers (int buf))
      (catch Exception e
        (log/warn "audio/cleanup! buffer error:" (.getMessage e)))))
  (reset! buffers #{})
  ;; Destroy context and device
  (when (not= -context (long 0))
    (ALC10/alcMakeContextCurrent (long 0))
    (ALC10/alcDestroyContext -context)
    (set! -context (long 0)))
  (when (not= -device (long 0))
    (ALC10/alcCloseDevice -device)
    (set! -device (long 0))))

;; ---------------------------------------------------------------------------
;; WAV loading via javax.sound.sampled
;; ---------------------------------------------------------------------------

(defn- load-wav
  "Load a WAV file and return {:data ByteBuffer :format int :sample-rate int}.
   Converts to 16-bit signed PCM mono or stereo as needed."
  [^String path]
  (let [file   (File. path)
        stream (AudioSystem/getAudioInputStream file)
        fmt    (.getFormat stream)
        rate   (int (.getSampleRate fmt))
        ;; Normalise to PCM_SIGNED 16-bit
        target-fmt (AudioFormat.
                    AudioFormat$Encoding/PCM_SIGNED
                    (float rate)
                    16
                    (int (.getChannels fmt))
                    (int (* (.getChannels fmt) 2))
                    (float rate)
                    false)     ; little-endian
        stream2  (AudioSystem/getAudioInputStream target-fmt stream)
        fmt2     (.getFormat stream2)
        channels (int (.getChannels fmt2))
        al-fmt   (if (= channels 1) AL10/AL_FORMAT_MONO16 AL10/AL_FORMAT_STEREO16)
        data     (.readAllBytes stream2)
        buf      (doto (ByteBuffer/allocateDirect (alength data))
                   (.order ByteOrder/LITTLE_ENDIAN)
                   (.put data)
                   (.flip))]
    {:data buf :format al-fmt :sample-rate rate}))

;; ---------------------------------------------------------------------------
;; load-sound!
;; ---------------------------------------------------------------------------

(defn load-sound!
  "Load a WAV file and upload to an OpenAL buffer.
   Returns the buffer id (long)."
  ^long [^String path]
  (log/info "audio/load-sound!" path)
  (let [buf-id (AL10/alGenBuffers)
        {:keys [data format sample-rate]} (load-wav path)]
    (AL10/alBufferData buf-id format ^ByteBuffer data (int sample-rate))
    (swap! buffers conj (long buf-id))
    (long buf-id)))

;; ---------------------------------------------------------------------------
;; play! / loop! / stop! / set-gain! / set-master-volume!
;; ---------------------------------------------------------------------------

(defn play!
  "Play a one-shot sound from buffer sound-id.
   The source is automatically cleaned up in tick! once it finishes."
  ^long [^long sound-id]
  (let [src (long (AL10/alGenSources))]
    (AL10/alSourcei (int src) AL10/AL_BUFFER (int sound-id))
    (AL10/alSourcei (int src) AL10/AL_LOOPING AL10/AL_FALSE)
    (AL10/alSourcePlay (int src))
    (swap! sources assoc src :one-shot)
    src))

(defn loop!
  "Play a looping sound from buffer sound-id. Returns source-id (long).
   Caller must call stop! to end looping."
  ^long [^long sound-id]
  (let [src (long (AL10/alGenSources))]
    (AL10/alSourcei (int src) AL10/AL_BUFFER (int sound-id))
    (AL10/alSourcei (int src) AL10/AL_LOOPING AL10/AL_TRUE)
    (AL10/alSourcePlay (int src))
    (swap! sources assoc src :loop)
    (long src)))

(defn stop!
  "Stop and delete a source."
  [^long source-id]
  (try
    (AL10/alSourceStop (int source-id))
    (AL10/alDeleteSources (int source-id))
    (catch Exception e
      (log/warn "audio/stop! error:" (.getMessage e))))
  (swap! sources dissoc source-id))

(defn set-gain!
  "Set gain (volume) on a source (0.0–1.0)."
  [^long source-id ^double gain]
  (AL10/alSourcef (int source-id) AL10/AL_GAIN (float gain)))

(defn set-master-volume!
  "Set the listener master volume (0.0–1.0)."
  [^double volume]
  (AL10/alListenerf AL10/AL_GAIN (float volume)))

;; ---------------------------------------------------------------------------
;; tick!
;; ---------------------------------------------------------------------------

(defn tick!
  "Scan one-shot sources and delete any that have finished playing.
   Call once per frame from the game loop."
  []
  (let [snap @sources]
    (doseq [[src kind] snap]
      (when (= kind :one-shot)
        (let [state (AL10/alGetSourcei (int src) AL10/AL_SOURCE_STATE)]
          (when (= state AL10/AL_STOPPED)
            (try
              (AL10/alDeleteSources (int src))
              (catch Exception e
                (log/warn "audio/tick! cleanup error:" (.getMessage e))))
            (swap! sources dissoc src)))))))
