(ns dysentery.view
  "Provides a way to inspect DJ Link packets, watch for changing
  values, and develop an understanding of what they mean."
  (:require [dysentery.finder :as finder]
            [dysentery.util :as util]
            [dysentery.vcdj :as vcdj]
            [clojure.math.numeric-tower :as math]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame JPanel JLabel SwingConstants]))

(def byte-font
  "The font to use for rendering byte values."
  (java.awt.Font. "Monospaced" java.awt.Font/PLAIN 14))

(def fade-steps
  "The number of packets over which the background of a value fades
  from blue back to black after it changes."
  10)

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
  [packet expected-length]
  (or (= (count packet) expected-length)
      (timbre/warn "Expecting packet of length" expected-length "but it has length" (count packet))))

(defn- correct-type-and-length?
  "Tests whether a packet has the type value we are expecting, and the
  correct number of bytes for that length. Logs a warning and returns
  false otherwise."
  [packet expected-type]
  (let [current-type (get packet 10)]
    (if (= current-type expected-type)
      (case expected-type
        0x0a (correct-length? packet 212)
        0x29 (correct-length? packet 56)
        (timbre/warn "Received packet with unrecognized type:" current-type))
      (timbre/warn "Expecting packet of type" expected-type "but received type" current-type))))

(def mixer-unknown
  "The bytes which we think come at the end of a mixer status packet,
  so we can make them red if we find something unexpected. The device
  number bytes are left as zero because they have already been
  colored."
  [0 0 0 0x14 0 0 0 0xd0 0 0x10 0 0 0x80 0 0x2e 0xe0 0 0x10 0 0 0 9 0])

(defn recognized-if
  "If `recognized` is truthy, returns green, otherwise red."
  [recognized]
  (if recognized java.awt.Color/green java.awt.Color/red))

(defn- mixer-byte-format
  "Given a packet which has been identified as coming from a mixer and a byte
  index that refers to one of the bytes not handled by [[byte-format]], see
  if it seems to be one that we expect, or something more surprising."
  [packet index value hex]
  (cond
    (#{46 47} index)  ; This is the current BPM
    [hex java.awt.Color/green]

    (= index 54)  ; We don't know what this is, so far we have seen values 00 and FF
    [hex (recognized-if (#{0x00 0xff} value))]

    (= index 55)  ; We think this is a beat number ranging from 1 to 4
    [hex (recognized-if (<= 1 value 4))]

    :else
    [hex (recognized-if (= value (get mixer-unknown (- index 32))))]))

(def cdj-unknown
  "Template for bytes in a CDJ packet which we don't yet have specific
  interpretations for, so we can at least color them red if they have
  a different value than we expect. Bytes with known interpretations
  are still present, but left as zero, to make it easier to index into
  the vector."
  [0x03 0x00 0x00 0xb0 0x00 0x00 0x01 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x01 0x00 0x04 0x04 0x00 0x00 0x00 0x04
   0x00 0x00 0x00 0x04 0x00 0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x31 0x2e 0x32 0x34
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x01 0x00 0x00 0xff 0x7e 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x7f 0xff 0xff 0xff 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0xff
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x10 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x0f 0x00 0x00 0x00
   0x00 0x00 0x00 0x00])

(defn- cdj-byte-format
  "Given a packet which has been identified as coming from a CDJ and a byte
  index that refers to one of the bytes not handled by [[byte-format]], see
  if it seems to be one that we expect, or something more surprising."
  [packet index value hex]
  (cond
    (= index 39)  ; Seems to be a binary activity flag?
    [hex (recognized-if (#{0 1} value))]

    (= index 106)  ; Alternates between 4 and 6?
    [hex (recognized-if (#{4 6} value))]

    (= index 117)  ; Seems to indicate USB media is mounted in any player?
    [hex (recognized-if (#{0 1} value))]

    (= index 123)  ; Play mode part 1
    [hex (recognized-if (#{0 3 4 5 6 9} value))]

    (= index 137)  ; State flags
    [hex (recognized-if (= (bit-and value 2r10000111) 2r10000100))]

    (#{141 153 193 197} index)  ; The first byte of the four pitch copies
    [hex (recognized-if (< value 0x21))] ; Valid pitces range from 0x000000 to 0x200000

    (#{142 143 154 155 194 195 198 199} index)  ; Later bytes of the four pitch copies
    [hex (java.awt.Color/green)]  ; All values are valid

    (= 144 index)  ; First byte of some kind of loaded indicator?
    [hex (recognized-if (or (and (= value 0x7f) (= (get packet (inc index)) 0xff))
                            (and (= value 0x80) (= (get packet (inc index)) 0x00))))]
    
    (= 145 index)  ; Second byte of some kind of loaded indicator?
    [hex (recognized-if (or (and (= value 0xff) (= (get packet (dec index)) 0x7f))
                            (and (= value 0x00) (= (get packet (dec index)) 0x80))))]
    
    (#{146 147} index)  ; The BPM
    [hex (java.awt.Color/green)]  ; All values are valid for now

    (= index 157)  ; Play mode part 2?
    [hex (recognized-if (#{0 1 9 13} value))]

    (= 158 index)  ; A simpler boolean loaded flag?
    [hex (recognized-if (or (and (zero? value) (zero? (get packet 123)))
                            (and (= 1 value) (pos? (get packet 123)))))]

    (<= 160 index 163)  ; The beat number within the track
    [hex (java.awt.Color/green)]

    (= index 164)  ; First byte of countdown to next memory point; always 0 or 1
    [hex (recognized-if (#{0 1} value))]

    (= index 165)  ; Second byte of countdown to next memory point
    [hex (java.awt.Color/green)]

    (= index 166)  ; We think this is a beat number ranging from 1 to 4, when track loaded.
    [hex (recognized-if (or (and (zero? value) (zero? (get packet 123)))
                            (and (<= 1 value 4) (pos? (get packet 123)))))]

    (<= 200 index 203)  ; Packet counter
    [hex (java.awt.Color/green)]

    :else
    [hex (if (= value (get cdj-unknown (- index 32))) java.awt.Color/green java.awt.Color/red)]))

(defn- byte-format
  "Given a device number, packet, expected packet type, and byte
  index, return the text and color that should be used to represent
  that byte in the packet based on our expectations."
  [device-number packet expected-type index]
  (let [current (get packet index)
        hex (format "%02x" current)]
    (cond
      (< index 10)  ; The standard header
      [hex (if (= current (get status-header index)) java.awt.Color/green java.awt.Color/red)]

      (= index 10)  ; The packet type
      [hex (if (correct-type-and-length? packet expected-type) java.awt.Color/green java.awt.Color/red)]

      (< index 31)  ; The device name
      [(if (pos? current) (str (char current)) "") java.awt.Color/green]

      (= index 31)  ; We don't know what this byte is but expect it to be 1
      [hex (if (= current 1) java.awt.Color/green java.awt.Color/red)]

      (#{33 36} index)  ; We expect these bytes to match the device number
      [hex (if (= current device-number) java.awt.Color/green java.awt.Color/red)]

      (= expected-type 0x29)
      (mixer-byte-format packet index current hex)

      (= expected-type 0x0a)
      (cdj-byte-format packet index current hex)

      :else
      [hex java.awt.Color/white])))

(defn- create-byte-labels
  "Create a set of labels which will display the content of the
  packet, byte by byte."
  [device-number packet expected-type]
  (vec (for [index (range (count packet))]
         (let [[value color] (byte-format device-number packet expected-type index)
               label (JLabel. value SwingConstants/CENTER)]
           (.setForeground label color)
           label))))

(defn- update-byte-labels
  "Update the content of the labels analyzing the packet when a new
  packet has been received."
  [device-number packet expected-type byte-labels freshness]
  (dotimes [index (count packet)]  ; We have a packet to update our state with
    (let [label (get byte-labels index)
          [value color] (byte-format device-number packet expected-type index)
          level (aget freshness index)]
      (when label
        (.setForeground label color)
        (if (.equals value (.getText label))
          (when (pos? level) ; Fade out the background, the value has not changed
            (.setBackground label (java.awt.Color. (int 0) (int 0)
                                                   (int (math/round (* 255 (/ (dec level) fade-steps))))))
            (aset freshness index (dec level)))
          (do ; The value has changed, update the label and set the background bright blue, setting up a fade
            (.setText label value)
            (.setBackground label (java.awt.Color/blue))
            (aset freshness index fade-steps)))))))

(defn- create-address-labels
  "Create a set of fixed labels which identify the addresses of the
  bytes shown, given the panel into which they should be installed,
  and the length of the packet."
  [panel packet-length]
  (doseq [x (range 16)]
    (let [label (JLabel. (format "%x" x) SwingConstants/CENTER)]
      (.setFont label byte-font)
      (.setForeground label java.awt.Color/yellow)
      (.add panel label)
      (.setBounds label (* (inc x) 25) 0 20 15)))
  (doseq [y (range (inc (quot (dec packet-length) 16)))]
    (let [label (JLabel. (format "%x" y) SwingConstants/RIGHT)]
      (.setFont label byte-font)
      (.setForeground label java.awt.Color/yellow)
      (.add panel label)
      (.setBounds label 0 (* (inc y) 15) 20 15))))

(defn position-byte-labels
  "Place the labels which will display individual packet bytes at the
  proper locations within the panel that will hold them."
  [byte-labels panel]
  (loop [labels byte-labels
         index 0]
    (when (seq labels)
      (let [label (first labels)
            x (rem index 16)
            y (quot index 16)
            left (* x 20)
            top (* y 14)]
        (.setFont label byte-font)
        (.setForeground label java.awt.Color/white)
        (.setBackground label java.awt.Color/black)
        (.setOpaque label true)
        (.add panel label)
        (.setBounds label (* (inc x) 25) (* (inc y) 15) 20 15))
      (recur (rest labels) (inc index)))))

(defn- create-player-frame
  "Creates a frame for displaying packets sent to the specified device
  number, and returns a function to be called to update the frame when
  a new packet is received for that device."
  [device-number packet]
  (let [original-packet-type (get packet 10)
        frame (JFrame. (str "Player " device-number))
        panel (JPanel.)
        byte-labels (create-byte-labels device-number packet original-packet-type)
        freshness (int-array (count packet))]
    (.setLayout panel nil)
    (.setSize frame 440 250)
    (.setContentPane frame panel)
    (.setBackground panel java.awt.Color/black)
    (.setDefaultCloseOperation frame JFrame/EXIT_ON_CLOSE)

    (create-address-labels panel (count packet))
    (position-byte-labels byte-labels panel)

    (let [location (.getLocation frame)
          offset (* 20 (inc (count @packet-frames)))]
      (.translate location offset offset)
      (.setLocation frame location))
    (.setVisible frame true)
    (fn [packet]
      (if (nil? packet)
        (do  ; We were told to shut down
          (.setVisible frame false)
          (.dispose frame))
        (update-byte-labels device-number packet original-packet-type byte-labels freshness)))))

(defn- handle-device-packet
  "Find and update or create the frame used to display packets from
  the device."
  [device-number packet]
  (if-let [frame (get @packet-frames device-number)]
    (frame packet)
    (swap! packet-frames assoc device-number (create-player-frame device-number packet))))

(defn- start-watching-devices
  "Once we have found some DJ-Link devices, set up a virtual CDJ to
  receive packets from them, and register a packet listener that will
  create or update windows to display those packets."
  [devices]
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (vcdj/start interface address)
    (vcdj/add-packet-listener handle-device-packet)))

(defn describe-devices
  "Print a descrption of the DJ Link devices found, and how to
  interact with them."
  [devices]
  (println "Found:")
  (doseq [device devices]
    (println "  " (:name device) (.toString (:address device))))
  (println)
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (println "To communicate create a virtual CDJ with address" (str (.toString (.getAddress address)) ","))
    (print "MAC address" (clojure.string/join ":" (map (partial format "%02x")
                                                       (map util/unsign (.getHardwareAddress interface)))))
    (println ", and use broadcast address" (.toString (.getBroadcast address)))))

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
  the network, to help analyze them."
  []
  (when-let [devices (seq (find-devices))]
    (describe-devices devices)
    (start-watching-devices devices)))

(defn stop-watching-devices
  "Remove the packet listener, close the frames, and shut down the
  virtual CDJ."
  []
  (vcdj/remove-packet-listener handle-device-packet)
  (vcdj/shut-down)
  (doseq [frame (vals @packet-frames)]
    (frame nil))
  (reset! packet-frames {}))
