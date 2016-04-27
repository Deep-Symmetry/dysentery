(ns dysentery.view
  "Provides a way to inspect DJ Link packets, watch for changing
  values, and develop an understanding of what they mean."
  (:require [dysentery.finder :as finder]
            [dysentery.util :as util]
            [dysentery.vcdj :as vcdj]
            [clojure.math.numeric-tower :as math])
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

(defn create-player-frame
  "Creates a frame for displaying packets sent to the specified device
  number, and returns a function to be called to update the frame when
  a new packet is received for that device."
  [device-number packet]
  (let [frame (JFrame. (str "Player " device-number))
        panel (JPanel.)
        byte-labels (mapv  #(JLabel. (format "%02x" %) SwingConstants/CENTER) packet)
        freshness (int-array (count packet))]
    (.setLayout panel nil)
    (doseq [x (range 16)]
      (let [label (JLabel. (format "%x" x) SwingConstants/CENTER)]
        (.setFont label byte-font)
        (.setForeground label java.awt.Color/yellow)
        (.add panel label)
        (.setBounds label (* (inc x) 25) 0 20 15)))
    (doseq [y (range (inc (quot (dec (count packet)) 16)))]
      (let [label (JLabel. (format "%x" y) SwingConstants/RIGHT)]
        (.setFont label byte-font)
        (.setForeground label java.awt.Color/yellow)
        (.add panel label)
        (.setBounds label 0 (* (inc y) 15) 20 15)))
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
        (recur (rest labels) (inc index))))

    (.setBackground panel java.awt.Color/black)
    (.setSize frame 440 250)
    (let [location (.getLocation frame)
          offset (* 20 (inc (count @packet-frames)))]
      (.translate location offset offset)
      (.setLocation frame location))
    (.add frame panel)
    (.setVisible frame true)
    (fn [packet]
      (if (nil? packet)
        (do  ; We were told to shut down
          (.setVisible frame false)
          (.dispose frame))
        (dotimes [index (count packet)]  ; We have a packet to update our state with
          (let [label (get byte-labels index)
                value (format "%02x" (get packet index))
                level (aget freshness index)]
            (when label
              (if (.equals value (.getText label))
                (when (pos? level) ; Fade out the background, the value has not changed
                  (.setBackground label (java.awt.Color. (int 0) (int 0)
                                                         (int (math/round (* 255 (/ (dec level) fade-steps))))))
                  (aset freshness index (dec level)))
                (do ; The value has changed, update the label and set the background bright blue, setting up a fade
                  (.setText label value)
                  (.setBackground label (java.awt.Color/blue))
                  (aset freshness index fade-steps))))))))))

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
