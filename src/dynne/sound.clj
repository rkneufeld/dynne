(ns dynne.sound
  "Functions for working with sounds"
  (:require [clojure.java.io :as io]
            [dynne.sound.impl :as impl]
            [incanter.core :as incanter]
            [incanter.charts :as charts])
  (:import [javax.sound.sampled
            AudioFileFormat$Type
            AudioFormat
            AudioFormat$Encoding
            AudioInputStream
            AudioSystem
            Clip]))

(defn channels
  "Return the number of channels in `sound`."
  [s]
  (if (satisfies? impl/ChannelCount s)
    (impl/num-channels s)
    (count (impl/amplitudes s 0.0))))

;; We reserve the right to do something other than fall through to the
;; implementation, so we park a function in this namespace.
(def ^{:doc "Return the duration of `s` in seconds."} duration impl/duration)

(def ^:private zero-frame
  "Returns a frame of all zeros with the same number of channels as s. Tries to be fast."
  (memoize (fn [s] (vec (repeat (channels s) 0.0)))))

(defn sample
  "Returns a vector of amplitues from sound `s` at time `t`, or zeros
  if `t` falls outside the sound's range. Call this in preference to
  using the sound's `amplitudes` implementation."
  [s t]
  (if (<= 0.0 t (duration s))
    (impl/amplitudes s t)
    (zero-frame s)))

(defn oversample
  "Returns the mean of sampling `s` `n` steps of `delta-t` around `t`."
  [s t n delta-t]
  (let [channels (channels s)
        acc (double-array channels)]
    (dotimes [i n]
      (let [frame (sample s (+ t (* delta-t (double i))))]
        (loop [frame frame
               c 0]
          (when frame
            (aset acc c (+ (aget acc c) (/ (first frame) (double n))))
            (recur (next frame) (inc c))))))
    (seq acc)))

;;; Sound construction

(defn sound
  "Creates a sound `duration` seconds long whose amplitudes are
  produced by `f`."
  [duration f]
  (let [channels (count (f 0.0))]
    (reify
      impl/Sound
      (duration [this] duration)
      (amplitudes [this t] (f t))

      impl/ChannelCount
      (num-channels [this] channels))))

(defn null-sound
  "Returns a zero-duration sound with one channel."
  []
  (sound 0.0 (constantly [0.0])))

(defn sinusoid
  "Returns a single-channel sound of `duration` and `frequency`."
  [duration ^double frequency]
  (sound duration
         (fn [^double t]
           [(Math/sin (* t frequency 2.0 Math/PI))])))

(defn square-wave
  "Produces a single-channel sound that toggles between 1.0 and -1.0
  at frequency `freq`."
  [duration freq]
  (sound duration
         (fn [t]
           (let [x (-> t (* freq 2.0) long)]
             (if (even? x) [1.0] [-1.0])))))

(defn linear
  "Produces a `n`-channel (default 1) sound whose samples move linearly
  from `start` to `end` over `duration`."
  ([^double duration ^double start ^double end] (linear duration start end 1))
  ([^double duration ^double start ^double end n]
     (let [span (double (- end start))]
       (sound duration
              (fn [^double t]
                (repeat n (+ start (* span (/ t duration)))))))))

(defn silence
  "Creates a `n`-channel sound (default 1) that is `duration` long but silent."
  ([duration] (silence duration 1))
  ([duration n] (sound duration (constantly (repeat n 0.0)))))

;;; File-based Sound

(defn- advance-frames
  "Reads and discards `n` frames from AudioInputStream `ais`. Returns
  the number of frames actually read."
  [^AudioInputStream ais n]
  (let [bytes-per-frame (-> ais .getFormat .getFrameSize)
        discard-frame-max 1000
        discard-buffer-bytes (* bytes-per-frame discard-frame-max)
        discard-buffer (byte-array discard-buffer-bytes)]
    (loop [total-frames-read (long 0)]
      (let [frames-left-to-read (- n total-frames-read)]
        (if (pos? frames-left-to-read)
          (let [frames-to-read (min discard-frame-max frames-left-to-read)
                bytes-to-read (* bytes-per-frame frames-to-read)
                bytes-read (.read ais discard-buffer (int 0) (int bytes-to-read))
                frames-read (long (/ bytes-read bytes-per-frame))]
            (if (neg? frames-read)
              total-frames-read
              (recur (+ total-frames-read frames-read))))
          total-frames-read)))))

(defn read-sound
  "Returns a Sound for the file at `path`."
  [path]
  (let [file                   (io/file path)
        in                     (atom (AudioSystem/getAudioInputStream file))
        base-format            (.getFormat ^AudioInputStream @in)
        base-file-format       (AudioSystem/getAudioFileFormat file)
        base-file-properties   (.properties base-file-format)
        base-file-duration     (get base-file-properties "duration")
        bits-per-sample        16
        bytes-per-sample       (/ bits-per-sample 8)
        channels               (.getChannels base-format)
        bytes-per-frame        (* bytes-per-sample channels)
        frames-per-second      (.getSampleRate base-format)
        decoded-format         (AudioFormat. AudioFormat$Encoding/PCM_SIGNED
                                             frames-per-second
                                             bits-per-sample
                                             channels
                                             (* bytes-per-sample channels)
                                             frames-per-second
                                             true)
        din                    (atom (AudioSystem/getAudioInputStream
                                      decoded-format
                                      ^AudioInputStream @in))
        decoded-length-seconds (if base-file-duration
                                 (/ base-file-duration 1000000.0)
                                 (/ (.getFrameLength ^AudioInputStream @din)
                                    frames-per-second))
        buffer-seconds         10
        buffer                 (byte-array (* frames-per-second
                                              buffer-seconds
                                              channels
                                              bytes-per-sample))
        buffer-start-pos       (atom nil)
        buffer-end-pos         (atom nil)
        bb                     (java.nio.ByteBuffer/allocate bytes-per-frame)
        frame-array            (double-array channels)]
    (reify
      impl/ChannelCount
      (num-channels [s] channels)

      impl/Sound
      (duration [s] decoded-length-seconds)
      (amplitudes [s t]
        (let [frame-at-t (-> t (* frames-per-second) long)]
          ;; Desired frame is before current buffer. Reset everything
          ;; to the start state
          (let [effective-start-of-buffer (or @buffer-start-pos -1)]
            (when (< frame-at-t effective-start-of-buffer)
              ;;(println "rewinding")
              (.close ^AudioInputStream @din)
              (.close ^AudioInputStream @in)
              (reset! in (AudioSystem/getAudioInputStream (io/file path)))
              (reset! din (AudioSystem/getAudioInputStream decoded-format ^AudioInputStream @in))
              (reset! buffer-start-pos nil)
              (reset! buffer-end-pos nil)))

          ;; Desired position is past the end of the buffered region.
          ;; Update buffer to include it.
          (let [effective-end-of-buffer (or @buffer-end-pos -1)]
            (when (< effective-end-of-buffer frame-at-t)
              (let [frames-to-advance (- frame-at-t effective-end-of-buffer 1)]
                ;; We can't skip, because there's state built up during .read
                ;; (println "Advancing to frame" frame-at-t
                ;;          "by going forward" frames-to-advance
                ;;          "frames")
                (let [frames-advanced (advance-frames @din frames-to-advance)]
                  (if (= frames-to-advance frames-advanced)
                    (let [bytes-read (.read ^AudioInputStream @din buffer)]
                      (if (pos? bytes-read)
                        (let [frames-read (/ bytes-read bytes-per-frame)]
                          (reset! buffer-start-pos frame-at-t)
                          (reset! buffer-end-pos (+ frame-at-t frames-read -1)))
                        (do
                          (reset! buffer-start-pos nil)
                          (reset! buffer-end-pos nil))))
                    (do
                      (reset! buffer-start-pos nil)
                      (reset! buffer-end-pos nil)))))))

          ;; Now we're either positioned or the requested position
          ;; cannot be found
          (if @buffer-end-pos
            (let [buffer-frame-offset (- frame-at-t @buffer-start-pos)
                  buffer-byte-offset (* buffer-frame-offset bytes-per-frame)]
              (.position bb 0)
              (.put bb buffer buffer-byte-offset bytes-per-frame)
              (.position bb 0)
              ;; TODO: We're hardcoded to .getShort here, but the
              ;; bits-per-frame is a parameter. Should probably have
              ;; something that knows how to read from a ByteBuffer
              ;; given a number of bits.
              (dotimes [i channels]
                (aset frame-array i (/ (double (.getShort bb)) (inc Short/MAX_VALUE))))
              (seq frame-array))
            (repeat channels 0))))

      java.io.Closeable
      (close [this]
        (.close ^AudioInputStream @din)
        (.close ^AudioInputStream @in)))))


;;; Sound manipulation

(defn multiplex
  "Turns a single-channel sound into a sound with the same signal on
  `n` channels."
  [s n]
  {:pre [(= 1 (channels s))]}
  (if (= (channels s) n)
    s
    (sound (duration s)
           (fn [t] (repeat n (first (sample s t)))))))

(defn ->stereo
  "Turns a sound into a two-channel sound. Currently works only on
  one- and two-channel inputs."
  [s]
  (case (long (channels s))
    1 (multiplex s 2)
    2 s
    (throw (ex-info "Can't stereoize sounds with other than one or two channels"
                    {:reason :cant-stereoize-channels :s s}))))

(defn multiply
  [s1 s2]
  "Multiplies two sounds together to produce a new one. Sounds must
  have the same number of channels."
  {:pre [(= (channels s1) (channels s2))]}
  (sound (min (duration s1) (duration s2))
         (fn [t] (mapv * (sample s1 t) (sample s2 t)))))

(defn pan
  "Takes a two-channel sound and mixes the channels together by
  `amount`, a float on the range [0.0, 1.0]. The ususal use is to take
  a sound with separate left and right channels and combine them so
  each appears closer to stereo center. An `amount` of 0.0 would leave
  both channels unchanged, 0.5 would result in both channels being the
  same, and 1.0 would switch the channels."
  [s amount]
  {:pre [(= 2 (channels s))]}
  (let [amount-complement (- 1.0 amount)]
    (sound (duration s)
           (fn [t]
             (let [[a b] (sample s t)]
               [(+ (* a amount-complement)
                   (* b amount))
                (+ (* a amount)
                   (* b amount-complement))])))))

(defn trim
  "Truncates `s` to the region between `start` and `end`."
  [s start end]
  (sound (- end start)
         (fn [^double t] (sample s (+ t start)))))

(defn mix
  "Mixes files `s1` and `s2`."
  [s1 s2]
  (sound (max (duration s1) (duration s2))
         (fn [t]
           (mapv + (sample s1 t) (sample s2 t)))))

(defn append
  "Concatenates sounds together."
  [s1 s2]
  (let [d1 (duration s1)
        d2 (duration s2)]
    (sound (+ d1 d2)
           (fn [^double t]
             (if (<= t d1)
               (sample s1 t)
               (sample s2 (- t d1)))))))

(defn timeshift
  "Inserts `amount` seconds of silence at the beginning of `s`"
  [s amount]
  (append (silence amount (channels s)) s))

(defn fade-in
  "Fades `s` linearly from zero at the beginning to full volume at
  `duration`."
  [s fade-duration]
  #_(multiply s (append (linear fade-duration 0 1.0) (linear (duration s) 1.0 1.0)))
  (multiply s (linear (duration s) 1.0 1.0 (channels s))))

(defn fade-out
  "Fades the s to zero for the last `duration`."
  [s fade-duration]
  (multiply s (append (linear (- (duration s) fade-duration) 1.0 1.0)
                      (linear fade-duration 1.0 0.0))))

(defn segmented-linear
  "Produces a single-channels sound whose amplitudes change linear as
  described by `spec`. Spec is a sequence of interleaved amplitudes
  and durations. For example the spec

  1.0 30
  0   10
  0   0.5
  1.0

  (written that way on purpose - durations and amplitudes are in columns)
  would produce a sound whose amplitude starts at 1.0, linearly
  changes to 0.0 at time 30, stays at 0 for 10 seconds, then ramps up
  to its final value of 1.0 over 0.5 seconds"
  [& spec]
  {:pre [(and (odd? (count spec))
              (< 3 (count spec)))]}
  (->> spec
       (partition 3 2)
       (map (fn [[start duration end]] (linear duration start end)))
       (reduce append)))

;;; Playback

(defn short-sample
  "Takes a floating-point number f in the range [-1.0, 1.0] and scales
  it to the range of a 16-bit integer. Clamps any overflows."
  [f]
  (let [f* (-> f (min 1.0) (max -1.0))]
    (short (* Short/MAX_VALUE f))))

(defn play
  "Plays `sound` asynchronously. Returns a function that, when called,
  will stop the sound playing."
  [s]
  (let [sample-rate  44100
        channels     (channels s)
        sdl          (AudioSystem/getSourceDataLine (AudioFormat. sample-rate
                                                                  16
                                                                  channels
                                                                  true
                                                                  true))
        buffer-bytes (* sample-rate channels) ;; Half-second
        bb           (java.nio.ByteBuffer/allocate buffer-bytes)
        total-bytes  (-> s duration (* sample-rate) long (* channels 2))
        byte->t      (fn [n] (-> n double (/ sample-rate channels 2)))
        stopped      (atom false)]
    (future
      (.open sdl)
      (loop [current-byte 0]
        (when (and (not @stopped) (< current-byte total-bytes))
          (let [bytes-remaining (- total-bytes current-byte)
                bytes-to-write (min bytes-remaining buffer-bytes)]
            (.position bb 0)
            (doseq [i (range 0 bytes-to-write (* 2 channels))]
              (let [t  (byte->t (+ current-byte i))
                    frame (oversample s t 4 (/ 1.0 sample-rate 4.0))]
                ;;(println t frame)
                (doseq [samp frame]
                  (.putShort bb (short-sample samp)))))
            (let [bytes-written (.write sdl (.array bb) 0 bytes-to-write)]
              (when-not @stopped (.start sdl))       ; Repeated calls are harmless
              (recur (+ current-byte bytes-written)))))))
    (fn []
      (reset! stopped true)
      (.stop sdl))))


;;; Serialization

(defn- sampled-input-stream
  "Returns an implementation of `InputStream` over `s`."
  [s sample-rate]
  (let [bits-per-sample   16
        marked-position  (atom nil)
        bytes-read       (atom 0)
        total-frames     (-> s duration (* sample-rate) long)
        channels         (channels s)
        bytes-per-sample (/ bits-per-sample 8)
        bytes-per-frame  (* channels bytes-per-sample)
        total-bytes      (* total-frames bytes-per-frame)]
    (proxy [java.io.InputStream] []
      (available [] (- total-bytes @bytes-read))
      (close [])
      (mark [readLimit] (reset! marked-position @bytes-read))
      (markSupported [] true)
      (read ^int
        ([] (throw (ex-info "Not implemented" {:reason :not-implemented})))
        ([^bytes buf] (read this buf 0 (alength buf)))
        ([^bytes buf off len]
           (if (<= total-bytes @bytes-read)
             -1
             (let [frames-to-read (/ len bytes-per-frame)
                   bytes-remaining (- total-bytes @bytes-read)
                   bytes-to-read (min len bytes-remaining)
                   bb (java.nio.ByteBuffer/allocate bytes-to-read)]
               (doseq [i (range 0 len bytes-per-frame)]
                 (let [t     (/ (double (+ i @bytes-read)) (* bytes-per-frame sample-rate))
                       ;; Oversample to smooth out some of the
                       ;; time-jitter that I think is introducing
                       ;; artifacts into the output a bit.
                       frame (oversample s t 4 (/ 1.0 sample-rate 4.0))]
                   (doseq [s frame]
                     (.putShort bb (* s Short/MAX_VALUE)))))
               (.position bb 0)
               (.get bb buf off len)
               (swap! bytes-read + bytes-to-read)
               bytes-to-read))))
      (reset [] (reset! bytes-read @marked-position))
      (skip [n] (swap! bytes-read + n)))))

(defn save
  "Save sound `s` to `path` as a 16-bit WAV with `sample-rate`."
  [s path sample-rate]
  (AudioSystem/write (AudioInputStream.
                      (sampled-input-stream s sample-rate)
                      (AudioFormat. sample-rate 16 (channels s) true true)
                      (-> s duration (* sample-rate) long))
                     AudioFileFormat$Type/WAVE
                     (io/file path)))


;;; Visualization

(defn visualize
  "Visualizes `s` by plottig it on a graph."
  ([s] (visualize s 0))
  ([s channel]
     (let [duration (duration s)]
       ;; TODO: Maybe use a function that shows power in a window
       ;; around time t rather than just the sample
       (incanter/view (charts/function-plot #(nth (sample s %) channel 0.0)
                                            0.0
                                            duration
                                            :step-size (/ duration 4000.0))))))
