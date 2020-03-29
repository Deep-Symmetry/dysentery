(ns dysentery.view
  "Provides a way to inspect DJ Link packets, watch for changing
  values, and develop an understanding of what they mean."
  (:require [clojure.java.io :as io]
            [dysentery.finder :as finder]
            [dysentery.util :as util]
            [dysentery.vcdj :as vcdj]
            [clojure.math.numeric-tower :as math]
            [selmer.parser :as parser]
            [taoensso.timbre :as timbre])
  (:import [java.awt Color Font]
           [java.awt.event WindowAdapter]
           [javax.swing JFrame JPanel JLabel SwingConstants]
           [java.net DatagramPacket DatagramSocket InetAddress]
           [java.util.concurrent TimeUnit]))

(def byte-font
  "The font to use for rendering byte values."
  (Font. "Monospaced" Font/PLAIN 14))

(def fade-time
  "The number of milliseconds over which the background of a label
  fades from blue to black after it has changed."
  1000)

(defonce ^{:doc "Set this atom to `true` if you want the program to exit
  when a packet window is closed."}
  exit-on-close-flag
  (atom false))

(defn exit-when-window-closed
  "Cause the program to exit when any packet capture window is
  closed."
  []
  (reset! exit-on-close-flag true))

(defonce ^{:private true
           :doc "A map whose keys are labels that have flashed blue
  because their value changed, and whose values are the time at which
  the value changed, so they can be faded back to black. Once they go
  black, they are removed from the map."}
  changed-labels (atom {}))

(defonce ^{:private true
           :doc "Holds the future which does the animation of label
  background colors so they can flash blue when changed and fade back
  to black."}
  animator (atom nil))

(defonce ^{:private true
           :doc "Holds a map of device numbers to the functions that
  should be called to update the corresponding informational frame
  when a packet is received from that device."}
  packet-frames (atom {}))

(def status-header
  "The bytes which are always expected at the beginning of a player
  status packet."
  [0x51 0x73 0x70 0x74 0x31 0x57 0x6d 0x4a 0x4f 0x4c])

(defn- correct-length?
  "Tests whether a packet has the length we are expecting. Logs a
  warning and returns nil otherwise."
  [packet expected-lengths]
  (or (expected-lengths (count packet))
      (timbre/warn "Expecting packet of length" expected-lengths "but it has length" (count packet))))

(defn- correct-type-and-length?
  "Tests whether a packet has the type value we are expecting, and the
  correct number of bytes for that length. Logs a warning and returns
  false otherwise."
  [packet expected-type]
  (let [current-type (get packet 10)]
    (if (= current-type expected-type)
      (case expected-type
        0x0a (correct-length? packet #{208 212 284 292})
        0x29 (correct-length? packet #{56})
        0x19 (correct-length? packet #{0x58})
        0x05 (correct-length? packet #{48})
        (timbre/warn "Received packet with unrecognized type:" current-type))
      (timbre/warn "Expecting packet of type" expected-type "but received type" current-type packet))))

(defn recognized-if
  "If `recognized` is truthy, returns green, otherwise red."
  [recognized]
  (if recognized Color/green Color/red))

(defn- mixer-50002-byte-format
  "Given a packet which has been identified as coming from a mixer and a byte
  index that refers to one of the bytes not handled by [[byte-format]], see
  if it seems to be one that we expect, or something more surprising."
  [_ index value hex]
  (cond
    (#{0x2e 0x2f} index)  ; This is the current BPM
    [hex Color/green]

    (= index 0x36)  ; Master handoff packet; 00 when there is no master, ff when no handoff taking place, player #
    [hex (recognized-if (#{0x00 0xff 1 2 3 4} value))]

    (= index 0x37)  ; Beat number ranging from 1 to 4
    [hex (recognized-if (<= 1 value 4))]

    :else
    [hex Color/white]))

(def mixer-status-flag-byte
  "The byte in packets that mixers send to port 50002 containing a set
  of status flag bits."
  39)

(def cdj-status-flag-byte
  "The byte in packets that CDJs send to port 50002 containing a set
  of status flag bits."
  137)

(def cdj-status-flag-playing-bit
  "The bit in the CDJ status flag byte which indicates it is currently
  in Play mode."
  2r01000000)

(def cdj-status-flag-master-bit
  "The bit in the CDJ status flag byte which indicates it is the master player."
  2r00100000)

(def cdj-status-flag-sync-bit
  "The bit in the CDJ status flag byte which indicates it is the master player."
  2r00010000)

(def cdj-status-flag-on-air-bit
  "The bit in the CDJ status flag byte which indicates it is on the
  air--that is, the mixer channel it is connected to is live and
  playing to the master output."
  2r00001000)

(def cdj-status-flag-bpm-sync-bit
  "The bit in the CDJ status flag byte which indicates it has degraded
  into BPM sync mode (that is, the DJ used pitch bend, so while it is
  still tracking the tempo of the master player, it is no longer
  aligning beats)."
  2r00000010)

(defonce ^{:private true
           :doc "Used to log beats from a specific player for more detailed analysis."}
  beat-log-config
  (atom {}))

(defn log-beats
  "Start logging details about the beat packets being received by the
  specified device, including timing information and unexpected
  differences, or, when called with no arguments or a `nil` device
  number, stop doing so."
  ([]
   (reset! beat-log-config {}))
  ([device-number ^String file-path]
   (if (not device-number)
     (log-beats)
     (do
       (spit file-path (str "Starting beat log for device " device-number " at " (java.util.Date.) "\n\n")
             :append true)
       (reset! beat-log-config {:device-number (byte device-number) :file-path file-path :start (System/nanoTime)})))))

(defn- format-upcoming-beat-time
  "Interprets 4 bytes at the specified packet offset as an integer,
  but returns an indication when no beat is forthcoming."
  [packet offset]
  (let [result (util/build-int packet offset 4)]
    (if (= result 0xffffffff)
      "---"
      result)))

(defn- format-arrival-time
  "Given a beat timestamp in nanoseconds, and the time at which logging
  began, format the beat arrival time as a number of seconds and
  milliseconds."
  [timestamp start]
  (let [ms (.toMillis TimeUnit/NANOSECONDS (- timestamp start))]
    (str (format "%3d" (.toSeconds TimeUnit/MILLISECONDS ms)) "."
         (format "%03d" (mod ms 1000)))))

(defn- scan-range
  "Makes sure that a range of bytes within a packet has an expected
  value, otherwise logs the fact that it did not, and the range
  itself."
  [packet start end expected log]
  (let [region (subvec packet start end)]
    (when (some #(not= expected %) region)
      (log (str "  *** Expected bytes " (format "%x" start) "-" (format "%x" (dec end))
                " to equal " (format "%02x" expected) ", got: "
                (clojure.string/join " " (map #(format "%02x" %) region)) " ***\n")))))

(defn- log-beat
  "Add a beat entry to the detailed beat log."
  [packet args config]
  (try
    (let [timestamp (System/nanoTime)
          log       (fn [text] (spit (:file-path config) text :append true))
          status    (when-let [prev (:last-status config)]
                      (let [interval (.toMillis TimeUnit/NANOSECONDS (- timestamp (:timestamp prev)))
                            s-args   (:args prev)
                            beat     (if (= :cdj (:type prev)) (format "%4d" (:beat s-args)) " n/a")]
                        (str " [" (:bar-beat s-args) " @status +" (format "%3d" interval) ", beat: " beat "]")))
          skew      (if-let [prev (:last-beat config)]
                      (let [interval (.toMillis TimeUnit/NANOSECONDS (- timestamp (:timestamp prev)))]
                        (str (format "%3d" (- interval (get-in prev [:args :next-beat]))) "ms"))
                      "  n/a")]
      (log (str "Beat at " (format-arrival-time timestamp (:start config)) ", skew: " skew))
      (log (str ", B_b: " (:bar-beat args) status ", BPM: " (:effective-bpm args) ", pitch: " (:pitch args) "\n"))
      (scan-range packet 0x3c 0x54 0xff log)
      (scan-range packet 0x58 0x5a 0x00 log)
      (scan-range packet 0x5d 0x5f 0x00 log)
      (swap! beat-log-config assoc :last-beat {:timestamp timestamp
                                               :packet    packet
                                               :args      args}))
    (catch Throwable t
      (timbre/warn t "Problem logging beat."))))

(defn calculate-pitch
  "Given a packet and the index of the first byte of a pitch value,
  calculate the pitch shift percentage that it represents."
  [packet index]
  (* 100.0 (/ (- (util/build-int packet index 3) 0x100000)
              0x100000)))

(defn format-cue-countdown
  "Given a number of beats before a cue point, format it the way the
  CDJ does."
  [beats]
  (cond
    (= beats 0x1ff)
    "--.-"

    (<= 1 beats 0x100)
    (format "%02d.%d" (quot (dec beats) 4) (inc (rem (dec beats) 4)))

    (zero? beats)
    "00.0"

    :else
    "??.?"))

(defn- update-cdj-50002-details-label
 "Updates the label that gives a detailed explanation of how we
  interpret the status of a CDJ given a packet sent to port 50002 and
  the panel in which that packet is being shown."
  [packet label]
  (let [flag-bits    (get packet cdj-status-flag-byte)
        track-bpm    (/ (util/build-int packet 146 2) 100.0)
        no-track?    (zero? (get packet 123))
        rekordbox-id (util/build-int packet 44 4)
        pitches      (mapv (partial calculate-pitch packet) [141 153 193 197])
        cue-distance (util/build-int packet 164 2)
        raw-beat     (util/build-int packet 160 4)
        beat         (if (= raw-beat 0xffffffff) "n/a" raw-beat)
        args         {:active           (get packet 39)
                      :rekordbox-device (get packet 40)
                      :rekordbox-slot   (case (get packet 41)
                                          0 "n/a"
                                          1 "CD"
                                          2 "SD"
                                          3 "USB"
                                          4 "collection"
                                          "???")
                      :rekordbox-type   (case (get packet 42)
                                          0 "unloaded"
                                          1 "rekordbox"
                                          2 "unanalyzed"
                                          5 "CD-DA"
                                          "???")
                      :rekordbox-id     (if (#{1 2 5} (get packet 42)) rekordbox-id "n/a")
                      :track            (util/build-int packet 50 2)
                      :usb-activity     (get packet 106)
                      :usb-local        (case (get packet 111)
                                          4 "Empty"
                                          2 "Unloading"
                                          0 "Loaded"
                                          "???")
                      :sd-local         (case (get packet 115)
                                          4 "Empty"
                                          2 "Unloading"
                                          0 "Loaded"
                                          "???")
                      :link-status      (if (pos? (get packet 117)) "Found" "Absent")
                      :p-1              (case (get packet 0x7b)
                                          0    "No Track"
                                          2    "Loading"
                                          3    "Playing"
                                          4    "Looping"
                                          5    "Stopped"
                                          6    "Cued"
                                          7    "Cue Play"
                                          8    "Cue Scratch"
                                          9    "Search"
                                          0x0e "Spun Down"
                                          0x11 "Ended"
                                          "???")
                      :p-2              (case (get packet 0x8b)
                                          0x6a "Play"
                                          0x6e "Stop"
                                          0x7a "nxs Play"
                                          0x7e "nxs Stop"
                                          0x9a "xz Play"
                                          0x9e "xz Stop"
                                          0xfa "nxs2 Play"
                                          0xfe "nxs2 Stop"
                                          "???")
                      :p-3              (case (get packet 0x9d)
                                          0    "No Track"
                                          1    "Stop or Reverse"
                                          9    "Forward Vinyl"
                                          0x0d "Forward CDJ"
                                          "???")

                      :empty-f       (zero? flag-bits)
                      :playing-flag  (pos? (bit-and flag-bits cdj-status-flag-playing-bit))
                      :master-flag   (pos? (bit-and flag-bits cdj-status-flag-master-bit))
                      :sync-flag     (pos? (bit-and flag-bits cdj-status-flag-sync-bit))
                      :on-air-flag   (pos? (bit-and flag-bits cdj-status-flag-on-air-bit))
                      :bpm-sync-flag (pos? (bit-and flag-bits cdj-status-flag-bpm-sync-bit))

                      :sync-n (util/build-int packet 134 2)

                      :bpm           (if no-track? "---" (format "%.1f" track-bpm))
                      :effective-bpm (if no-track? "---"
                                         (format "%.1f" (+ track-bpm (* track-bpm 1/100 (first pitches)))))
                      :pitches       (mapv (partial format "%+.2f%%") pitches)
                      :beat          beat
                      :bar-beat      (get packet 166)
                      :bar-image     (io/resource (str "images/Bar" (get packet 166) ".png"))
                      :mem           (format-cue-countdown cue-distance)
                      :near-cue      (< cue-distance 17)
                      :packet        (util/build-int packet 200 4)}]
    (.setText label (parser/render-file "templates/cdj-50002.tmpl" args))
    (when (:master-flag args)
      (vcdj/saw-master-packet (get packet 0x21) (util/build-int packet 0x84 4) (get packet 0x9f)))
    (when (= (get packet 0x21) (:device-number @beat-log-config))
      (swap! beat-log-config assoc :last-status {:type      :cdj
                                                 :timestamp (System/nanoTime)
                                                 :packet    packet
                                                 :args      (dissoc args :bar-image)})))
  label)

(def timestamp-formatter
  "Formats timestamps of incoming packets in the way we want them."
  (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS"))

(defn- create-timestamp-label
  "Creates labels that show when a packet was received."
  [panel]
  (let [label (JLabel. (.format timestamp-formatter (java.util.Date.)) SwingConstants/CENTER)]
    (.setFont label byte-font)
    (.setForeground label Color/yellow)
    (.setBackground label Color/black)
    (.setOpaque label true)
    (.add panel label)))

(defn- update-timestamp-label
  "Sets the label to show the current time and flashes its background
  blue."
  [label]
  (let [now (System/currentTimeMillis)]
    (.setText label (.format timestamp-formatter now))
    (.setBackground label Color/blue)
    (swap! changed-labels assoc label now)))

(defn- create-cdj-50002-details-label
  "Creates labels that give a detailed explanation of how we interpret
  the status of a CDJ given a packet sent to port 50002 and the panel
  in which that packet is being shown."
  [packet panel]
  (let [label (JLabel. "" SwingConstants/CENTER)]
    (.setVerticalAlignment label SwingConstants/TOP)
    (.add panel label)
    (update-cdj-50002-details-label packet label)))

(defn- update-mixer-50002-details-label
 "Updates the label that gives a detailed explanation of how we
  interpret the status of a mixer given a packet sent to port 50002
  and the panel in which that packet is being shown."
  [packet label]
  (let [flag-bits (get packet mixer-status-flag-byte)
        args      {:bpm          (format "%.1f" (/ (util/build-int packet 46 2) 100.0))
                   :empty-f      (zero? flag-bits)
                   :playing-flag (pos? (bit-and flag-bits cdj-status-flag-playing-bit))
                   :master-flag  (pos? (bit-and flag-bits cdj-status-flag-master-bit))
                   :sync-flag    (pos? (bit-and flag-bits cdj-status-flag-sync-bit))
                   :bar-beat     (get packet 55)
                   :bar-image    (clojure.java.io/resource (str "images/Bar" (get packet 55) ".png"))}]
    (.setText label (parser/render-file "templates/mixer-50002.tmpl" args))
    (when (:master-flag args)
      (vcdj/saw-master-packet (get packet 0x21) nil (get packet 0x36)))
    (when (= (get packet 0x21) (:device-number @beat-log-config))
      (swap! beat-log-config assoc :last-status {:type      :mixer
                                                 :timestamp (System/nanoTime)
                                                 :packet    packet
                                                 :args      (dissoc args :bar-image)})))
  label)

(defn- create-mixer-50002-details-label
  "Creates labels that give a detailed explanation of how we interpret
  the status of a mixer given a packet sent to port 50002 and the panel
  in which that packet is being shown."
  [packet panel]
  (let [label (JLabel. "" SwingConstants/CENTER)]
    (.setVerticalAlignment label SwingConstants/TOP)
    (.add panel label)
    (update-mixer-50002-details-label packet label)))

(defn- cdj-50002-byte-format
  "Given a packet to port 50002 which has been identified as coming
  from a CDJ and a byte index that refers to one of the bytes not
  handled by [[byte-format]], see if it seems to be one that we
  expect, or something more surprising."
  [packet index value hex]
  (cond
    (= index 39)  ; Seems to be a binary activity flag?
    [hex (recognized-if (#{0 1} value))]

    (= index 40)  ; Player from which track was loaded
    [hex (recognized-if (#{0 1 2 3 4 17 18 33 41} value))]

    (= index 41)  ; Slot from which track was loaded
    [hex (recognized-if (#{0 1 2 3 4} value))]

    (= index 42)  ; Track type? 0= none, 1 = rekordbox, 2 = unanalyzed, 5 = audio CD
    [hex (recognized-if (#{0 1 2 5} value))]

    (<= 44 index 47)  ; Rekordbox ID of the track
    [hex (Color/green)]

    (#{50 51} index)  ; Track index
    [hex (Color/green)]  ; All values are valid

    (= index 0x37)  ; CD slot status, or track loaded from playlist/menu.
    [hex (recognized-if (#{0 5 0x11 0x1e} value))]

    (= index 106)  ; USB actvity. Alternates between 4 and 6 when USB in use.
    [hex (recognized-if (#{4 6} value))]

    (= index 107)  ; SD actvity. Alternates between 4 and 6 when SD in use.
    [hex (recognized-if (#{4 6} value))]

    (= index 111)  ; Seems to indicate USB media status in the current player?
    [hex (recognized-if (#{0 2 4} value))] ; 4=no USB, 0=USB mounted, 2=unmounting USB

    (= index 115)  ; Seems to indicate SD media status in the current player?
    [hex (recognized-if (#{0 2 4} value))] ; 4=no SD, 0=SD mounted, 2=unmounting SD

    (= index 117)  ; Seems to indicate linkable media is mounted in any player?
    [hex (recognized-if (or (and (zero? value) (= 4 (get packet 111)) (= 4 (get packet 115)))
                            (pos? value)))]

    (= index 0x7b)  ; Play mode part 1
    [hex (recognized-if (#{0 2 3 4 5 6 7 8 9 0x0e 0x11} value))]

    (<= 124 index 127)  ; Firmware version, in ascii
    [(if (pos? value) (str (char value)) "") Color/green]

    (#{134 135} index)  ; Sync counter
    [hex (Color/green)]  ; All values are valid

    (= index 137)  ; State flags
    [hex (recognized-if (or (= (bit-and value 2r10000101) 2r10000100)
                            (and (zero? value) (= 208 (count packet)))))]

    (= index 0x8b)  ; Play mode part 2?
    [hex (recognized-if (#{0x6a 0x7a 0x6e 0x7e 0x9a 0x9e 0xfa 0xfe} value))]

    (#{141 153 193 197} index)  ; The first byte of the four pitch copies
    [hex (recognized-if (< value 0x80))] ; Valid pitces range from 0x000000 to 0x200000 but can scratch much faster

    (#{142 143 154 155 194 195 198 199} index)  ; Later bytes of the four pitch copies
    [hex (Color/green)]  ; All values are valid

    (= 144 index)  ; First byte of some kind of loaded indicator?
    [hex (recognized-if (or (and (= value 0x7f) (= (get packet (inc index)) 0xff))
                            (and (#{0x00 0x80} value) (zero? (get packet (inc index))))))]

    (= 145 index)  ; Second byte of some kind of loaded indicator?
    [hex (recognized-if (or (and (= value 0xff) (= (get packet (dec index)) 0x7f))
                            (and (zero? value) (#{0x00 0x80} (get packet (dec index))))))]

    (#{146 147} index)  ; The BPM
    [hex (Color/green)]  ; All values are valid for now

    (= index 157)  ; Play mode part 3?
    [hex (recognized-if (#{0 1 9 13} value))]

    (= index 0x9e)  ; Master mode
    [hex (recognized-if (#{0 1 2} value))]

    (= index 0x9f)  ; Master handoff
    [hex (recognized-if (#{1 2 3 4 0xff} value))]

    (<= 160 index 163)  ; The beat number within the track
    [hex (Color/green)]

    (= index 164)  ; First byte of countdown to next memory point; always 0 or 1
    [hex (recognized-if (#{0 1} value))]

    (= index 165)  ; Second byte of countdown to next memory point
    [hex (Color/green)]

    (= index 166)  ; We think this is a beat number ranging from 1 to 4, when analyzed track loaded.
    [hex (recognized-if (or (and (zero? value) (or (every? #(= 0xff %) (subvec packet 160 164))))
                            (and (<= 1 value 4) (some #(not= 0xff %) (subvec packet 160 164)))))]

    (<= 200 index 203)  ; Packet counter
    [hex (Color/green)]

    (= index 204)  ; Seems to be 0x0f for nexus players, 0x1f for the XDJ-XZ, 0x05 for others?
    [hex (recognized-if (or (and (#{0x0f 0x1f} value) (#{212 284} (count packet)))
                            (and (= value 0x05) (= 208 (count packet)))))]

    :else
    [hex Color/white]))

(defn- packet-50002-byte-format
  "Given a device number, expected packet type, packet, and byte
  index, return the text and color that should be used to represent
  that byte in the packet based on our expectations."
  [device-number expected-type packet index]
  (let [current (get packet index)
        hex (format "%02x" current)]
    (cond
      (< index 10)  ; The standard header
      [hex (if (= current (get status-header index)) Color/green Color/red)]

      (= index 10)  ; The packet type
      [hex (if (correct-type-and-length? packet expected-type) Color/green Color/red)]

      (< index 31)  ; The device name
      [(if (pos? current) (str (char current)) "") Color/green]

      (= index 31)  ; We don't know what this byte is but expect it to be 1
      [hex (if (= current 1) Color/green Color/red)]

      (#{33 36} index)  ; We expect these bytes to match the device number
      [hex (if (= current device-number) Color/green Color/red)]

      (= expected-type 0x29)
      (mixer-50002-byte-format packet index current hex)

      (= expected-type 0x0a)
      (cdj-50002-byte-format packet index current hex)

      :else
      [hex Color/white])))

(defn- packet-50001-byte-format
  "Given a device number, packet, and byte index, return the text and
  color that should be used to represent that byte in the packet based
  on our expectations."
  [device-number packet index]
  (let [current (get packet index)
        hex (format "%02x" current)]
    (cond
      (< index 10)  ; The standard header
      [hex (if (= current (get status-header index)) Color/green Color/red)]

      (= index 10)  ; The packet type; seems to always be 28 for these
      [hex (if (= current 0x28) Color/green Color/red)]

      (< index 31)  ; The device name
      [(if (pos? current) (str (char current)) "") Color/green]

      (= index 31)  ; We don't know what this byte is but expect it to be 1
      [hex (if (= current 1) Color/green Color/red)]

      (#{33 95} index)  ; We expect these to match the device number
      [hex (if (= current device-number) Color/green Color/red)]

      (<= 0x24 index 0x3b)  ; Next beat / bar timings
      [hex (Color/green)]

      (= index 85)  ; The first byte of the pitch
      [hex (recognized-if (< current 0x21))] ; Valid pitces range from 0x000000 to 0x200000

      (#{86 87} index)  ; Later bytes of the pitch
      [hex (Color/green)]  ; All values are valid

      (#{90 91} index)  ; This is the current BPM
      [hex Color/green]

      (= index 92)  ; Beat number ranging from 1 to 4
      [hex (recognized-if (<= 1 current 4))]

      :else
      [hex Color/white])))

(defn- create-byte-labels
  "Create a set of labels which will display the content of the
  packet, byte by byte, given the packet and a function which knows
  how to format each packet byte."
  [packet byte-formatter]
  (vec (for [index (range (count packet))]
         (let [[value color] (byte-formatter packet index)
               label (JLabel. value SwingConstants/CENTER)]
           (.setForeground label color)
           label))))

(defn- update-byte-labels
  "Update the content of the labels analyzing the packet when a new
  packet has been received."
  [packet byte-labels byte-formatter]
  (dotimes [index (count packet)]  ; We have a packet to update our state with
    (let [label (get byte-labels index)
          [value color] (byte-formatter packet index)]
      (when label
        (.setForeground label color)
        (when-not (.equals value (.getText label))
          ;; The value has changed, update the label and set the background bright blue, setting up a fade
          (.setText label value)
          (.setBackground label (Color/blue))
          (swap! changed-labels assoc label (System/currentTimeMillis)))))))

(defn- create-address-labels
  "Create a set of fixed labels which identify the addresses of the
  bytes shown, given the panel into which they should be installed,
  and the length of the packet."
  [panel packet-length]
  (doseq [x (range 16)]
    (let [label (JLabel. (format "%x" x) SwingConstants/CENTER)]
      (.setFont label byte-font)
      (.setForeground label Color/yellow)
      (.add panel label)
      (.setBounds label (* (inc x) 25) 0 20 15)))
  (doseq [y (range (inc (quot (dec packet-length) 16)))]
    (let [label (JLabel. (format "%x" y) SwingConstants/RIGHT)]
      (.setFont label byte-font)
      (.setForeground label Color/yellow)
      (.add panel label)
      (.setBounds label 0 (* (inc y) 15) 20 15))))

(defn- position-byte-labels
  "Place the labels which will display individual packet bytes at the
  proper locations within the panel that will hold them."
  [byte-labels panel]
  (loop [labels byte-labels
         index 0]
    (when (seq labels)
      (let [label (first labels)
            x (rem index 16)
            y (quot index 16)
            left (* (inc x) 25)
            top (* (inc y) 15)]
        (.setFont label byte-font)
        (.setForeground label Color/white)
        (.setBackground label Color/black)
        (.setOpaque label true)
        (.add panel label)
        (.setBounds label left top 20 15))
      (recur (rest labels) (inc index)))))

(defn- create-player-50002-frame
  "Creates a frame for displaying packets sent by the specified device
  number to port 50002, and returns a function to be called to update
  the frame when a new packet is received for that device."
  [device-number packet]
  (let [original-packet-type (get packet 10)
        frame (JFrame. (if (zero? device-number)
                         "We sent to port 50002"
                         (str "Player " device-number ", port 50002")))
        panel (JPanel.)
        num-byte-rows (inc (quot (dec (count packet)) 16))
        byte-labels (create-byte-labels packet (partial packet-50002-byte-format device-number original-packet-type))
        timestamp-label (create-timestamp-label panel)
        timestamp-top (* (inc num-byte-rows) 15)
        details-label (case original-packet-type
                        0x0a (create-cdj-50002-details-label packet panel)
                        0x29 (create-mixer-50002-details-label packet panel)
                        nil)
        details-height (if details-label 230 0)]
    (.setLayout panel nil)
    (.setSize frame 440 (+ timestamp-top 20 details-height))
    (.setContentPane frame panel)
    (.setBackground panel Color/black)
    (.setDefaultCloseOperation frame JFrame/DISPOSE_ON_CLOSE)
    (.addWindowListener frame (proxy [WindowAdapter] []
                                (windowClosed [_]
                                  (swap! packet-frames dissoc [50002 device-number])
                                  (when @exit-on-close-flag
                                    (System/exit 0)))))

    (create-address-labels panel (count packet))
    (position-byte-labels byte-labels panel)
    (.setBounds timestamp-label 0 timestamp-top 440 18)
    (when details-label (.setBounds details-label 0 (+ timestamp-top 20) 440 details-height))

    (let [location (.getLocation frame)
          offset (* 20 (inc (count @packet-frames)))]
      (.translate location offset offset)
      (.setLocation frame location)
      (.setVisible frame true)
      (fn [packet]
        (if (nil? packet)
          (do  ; We were told to shut down
            (.setVisible frame false)
            (.dispose frame))
          (do  ; We have an actual packet to display
            (update-timestamp-label timestamp-label)
            (update-byte-labels packet byte-labels
                                (partial packet-50002-byte-format device-number original-packet-type))
            (when (and details-label (= original-packet-type (get packet 10)))
              (case original-packet-type
                0x0a (update-cdj-50002-details-label packet details-label)
                0x29 (update-mixer-50002-details-label packet details-label)))))))))

(defn- update-player-50001-details-label
  "Updates the label that gives a detailed explanation of how we
  interpret the status of a player given a packet sent to port 50001
  and the panel in which that packet is being shown."
  [packet label]
  (let [pitch     (calculate-pitch packet 85)
        track-bpm (/ (util/build-int packet 90 2) 100.0)
        args      {:bpm           (format "%.1f" track-bpm)
                   :effective-bpm (format "%.1f" (+ track-bpm (* track-bpm 1/100 pitch)))
                   :pitch         (format "%+.2f%%" pitch)
                   :bar-beat      (get packet 92)
                   :next-beat     (format-upcoming-beat-time packet 0x24)
                   :2nd-beat      (format-upcoming-beat-time packet 0x28)
                   :next-bar      (format-upcoming-beat-time packet 0x2c)
                   :4th-beat      (format-upcoming-beat-time packet 0x30)
                   :2nd-bar       (format-upcoming-beat-time packet 0x34)
                   :8th-beat      (format-upcoming-beat-time packet 0x38)
                   :bar-image     (clojure.java.io/resource (str "images/Bar" (get packet 92) ".png"))}
        config    @beat-log-config]
    (when (= (get packet 0x21) (:device-number config))
      (log-beat packet (dissoc args :bar-image) config))
    (.setText label (parser/render-file "templates/player-50001.tmpl" args)))
  label)

(defn- create-player-50001-details-label
  "Creates labels that give a detailed explanation of how we interpret
  the status of a player given a packet sent to port 50001 and the panel
  in which that packet is being shown."
  [packet panel]
  (let [label (JLabel. "" SwingConstants/CENTER)]
    (.setVerticalAlignment label SwingConstants/TOP)
    (.add panel label)
    (update-player-50001-details-label packet label)))

(defn- create-player-50001-frame
  "Creates a frame for displaying packets sent by the specified device
  number to port 50001, and returns a function to be called to update
  the frame when a new packet is received for that device."
  [device-number packet]
  (let [frame (JFrame. (str "Player " device-number ", port 50001"))
        panel (JPanel.)
        byte-labels (create-byte-labels packet (partial packet-50001-byte-format device-number))
        timestamp-label (create-timestamp-label panel)
        details-label (create-player-50001-details-label packet panel)]
    (.setLayout panel nil)
    (.setSize frame 440 300)
    (.setContentPane frame panel)
    (.setBackground panel Color/black)
    (.setDefaultCloseOperation frame JFrame/DISPOSE_ON_CLOSE)
    (.addWindowListener frame (proxy [WindowAdapter] []
                                (windowClosed [_]
                                  (swap! packet-frames dissoc [50001  device-number])
                                  (when @exit-on-close-flag
                                    (System/exit 0)))))

    (create-address-labels panel (count packet))
    (position-byte-labels byte-labels panel)
    (.setBounds timestamp-label 0 115 440 18)
    (.setBounds details-label 0 145 440 200)

    (let [location (.getLocation frame)
          offset (* 20 (inc (count @packet-frames)))]
      (.translate location offset offset)
      (.setLocation frame location)
      (.setVisible frame true)
      (fn [packet]
        (if (nil? packet)
          (do  ; We were told to shut down
            (.setVisible frame false)
            (.dispose frame))
          (do  ; We have an actual packet to display
            (update-timestamp-label timestamp-label)
            (update-byte-labels packet byte-labels
                                (partial packet-50001-byte-format device-number))
            (update-player-50001-details-label packet details-label)))))))

(defn- create-player-frame
  "Creates a frame for displaying packets sent to the specified port
  by the specified device number, and returns a function to be called
  to update the frame when a new packet is received on that port for
  that device."
  [port device-number packet]
  (case port
    50001 (create-player-50001-frame device-number packet)
    50002 (create-player-50002-frame device-number packet)))

(defn handle-device-packet
  "Find and update or create the frame used to display packets on the
  specified port from the specified device."
  [port device-number packet]
  (if-let [frame (get @packet-frames [port device-number])]
    (frame packet)
    (swap! packet-frames assoc [port device-number] (create-player-frame port device-number packet))))

(defonce ^{:private true
           :doc "Holds the persistent server socket for receiving
  packets broadcast to port 50001 and the future that processes
  packets."}
  watcher-state (atom {:socket nil
                       :watcher nil}))

(defn stop-watching-devices
  "Remove the packet listener, close the frames, and shut down the
  virtual CDJ."
  []
  (vcdj/remove-packet-listener handle-device-packet)
  (vcdj/shut-down)
  (swap! watcher-state (fn [current]
                         (-> current
                             (update-in [:socket] #(when %
                                                     (try (.close %)
                                                          (catch Exception e
                                                            (timbre/warn e "Problem closing port 50001 socket.")))
                                                     nil))
                             (update-in [:watcher] #(when %
                                                      (try (future-cancel %)
                                                           (catch Exception e
                                                             (timbre/warn e "Problem stopping port 50001 watcher.")))
                                                      nil)))))
  (doseq [frame (vals @packet-frames)]
    (frame nil))
  (reset! packet-frames {}))

(defn- handle-sync-command
  "Reacts to a packet commanding us to change sync state."
  [command]
  (case command
    0x10 (vcdj/set-sync-mode true)
    0x20 (vcdj/set-sync-mode false)
    0x01 (vcdj/become-master)
    (timbre/warn "Unrecognized sync command value received:" command)))

(defn- handle-on-air-command
  "Reacts to a packet setting the on-air status of players."
  [packet]
  (vcdj/handle-on-air-packet packet))

(defn- handle-special-command
  "Checks whether a packet sent to port 50001 is actually a sync/master
  or on-air instruction, and if so reacts appropriately and returns a
  truthy value."
  [packet]
  (let [packet-type (get packet 10)]
    #_(timbre/info "packet-type" packet-type)
    (case packet-type
      0x03 (do
             (handle-on-air-command packet)
             true)
      0x2a (do
             (handle-sync-command (last packet))
             true)
      0x26 (do
             (vcdj/yield-master-to (last packet))
             true)
      0x27 (do
             (vcdj/master-yield-response (last packet) (get packet 0x21))
             true)
      false)))

(defn- receive
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload packet."
  [^DatagramSocket socket]
  (let [buffer (byte-array 512)
        packet (DatagramPacket. buffer 512)]
    (try (.receive socket packet)
         packet
         (catch Exception e
           (timbre/error e "Problem reading from DJ Link socket, shutting down.")
           (stop-watching-devices)))))

(defn- start-watching-devices
  "Once we have found some DJ-Link devices, set up a virtual CDJ to
  receive packets on port 50002 from them, create our own socket to
  monitor packets broadcast to port 50001, and register packet
  listeners that will create or update windows to display those
  packets."
  [devices device-name player-number]
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (vcdj/start interface address :device-name device-name :player-number player-number)
    (vcdj/add-packet-listener (partial handle-device-packet 50002)))
  (try
    (swap! watcher-state
           (fn [current]
             (let [socket (DatagramSocket. 50001 (InetAddress/getByName "0.0.0.0"))]
               (-> current
                   (assoc :socket socket)
                   (assoc :watcher
                          (future (loop []
                                    (let [packet (receive socket)
                                          data (vec (map util/unsign (take (.getLength packet) (.getData packet))))]
                                      (if (= 96 (.getLength packet))  ; A beat packet to display
                                        (handle-device-packet 50001 (get data 33) data)
                                        (when-not (handle-special-command data)
                                          #_(timbre/warn "Unrecognized port 50001 packet received, type:"
                                                         (get data 33)))))
                                    (recur))))))))
    (catch Exception e
      (timbre/warn e "Failed while trying to set up DJ-Link reception.")
      (stop-watching-devices))))

(defn describe-devices
  "Print a descrption of the DJ Link devices found, and how to
  interact with them."
  [devices]
  (println "Found:")
  (doseq [device devices]
    (println "  " (:name device) (:player device) (str (:address device))))
  (println)
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (println "To communicate create a virtual CDJ with address" (str (.getAddress address) ","))
    (print "MAC address" (clojure.string/join ":" (map (partial format "%02x")
                                                       (map util/unsign (.getHardwareAddress interface)))))
    (println ", and use broadcast address" (str (.getBroadcast address)))))

(defn find-devices
  "Run a loop that waits a few seconds to see if any DJ Link devices
  can be found on the network. If so, describe them and how to reach
  them."
  []
  (println "Looking for DJ Link devices...")
  (finder/start-if-needed)
  (Thread/sleep 2000)
  (loop [devices (finder/current-dj-link-devices)
         tries 3]
    (if (seq devices)
      devices
      (if (zero? tries)
        (println "No DJ Link devices found; giving up.")
        (do
          (Thread/sleep 1000)
          (recur (finder/current-dj-link-devices) (dec tries)))))))

(defn watch-devices
  "Create windows that show packets coming from all DJ-Link devices on
  the network, to help analyze them. The player number and device name
  of the virtual CDJ used to obtain status packets can be set using
  optional keyword arguments `:player-number` and `:device-name`. If
  you want to experiment with metadata requests, you must override the
  player number to fall between 1 and 4 (and not have any actual
  player with that number on the network)."
  [& {:keys [device-name player-number] :or {device-name "Virtual CDJ" player-number 5}}]
  (when-let [devices (seq (find-devices))]
    (describe-devices devices)
    (swap! animator (fn [current]
                      (or current
                          (future (loop [now (System/currentTimeMillis)]
                                    (doseq [[label changed] @changed-labels]
                                      (let [age (- now changed)]
                                        (if (> age fade-time)
                                          (do
                                            (.setBackground label Color/black)
                                            (swap! changed-labels dissoc label))
                                          (.setBackground label (Color. (int 0) (int 0)
                                                                        (int (math/round (* 255 (/ (- fade-time age)
                                                                                                   fade-time)))))))))
                                    (Thread/sleep 50)
                                    (recur (System/currentTimeMillis)))))))
    (start-watching-devices devices device-name player-number)))
