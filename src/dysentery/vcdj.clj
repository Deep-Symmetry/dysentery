(ns dysentery.vcdj
  "Provides the ability to create a virtual CDJ device that can lurk
  on a Pro DJ Link network and receive packets sent to players, so
  details about the other players can be monitored."
  {:author "James Elliott"}
  (:require [clojure.math.numeric-tower :as math]
            [dysentery.util :as util]
            [taoensso.timbre :as timbre]
            [dysentery.finder :as finder])
  (:import [java.net InetAddress DatagramPacket DatagramSocket NetworkInterface]))

(def incoming-port
  "The UDP port on which player unicast packets are received."
  50002)

(defonce ^{:private true
           :doc "Holds the persistent server socket, and the futures
  that process incoming packets and keep our presence active on the
  network."}
  state (atom {:socket nil
               :watcher nil
               :keep-alive nil}))

(defn shut-down
  "Close the UDP server socket and terminate the packet processing
  thread, if they are active."
  []
  (swap! state (fn [current]
                 (-> current
                     (update-in [:socket] #(when %
                                             (try (.close %)
                                                  (catch Exception e
                                                    (timbre/warn e "Problem closing DJ-Link player socket.")))
                                             nil))
                     (update-in [:watcher] #(when %
                                              (try (future-cancel %)
                                                   (catch Exception e
                                                     (timbre/warn e "Problem stopping DJ-Link player receiver.")))
                                              nil))
                     (update-in [:keep-alive] #(when %
                                              (try (future-cancel %)
                                                   (catch Exception e
                                                     (timbre/warn e "Problem stopping DJ-Link player keep-alive.")))
                                              nil)))))
  nil)

(defn- receive
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload packet."
  [^DatagramSocket socket]
  (let [buffer (byte-array 512)
        packet (DatagramPacket. buffer 512)]
    (try (.receive socket packet)
         packet
         (catch Exception e
           (timbre/warn e "Problem reading from DJ Link player socket, shutting down.")
           (shut-down)))))

(defonce ^{:private true
           :doc "Holds a set of functions to call whenever a packet
  has been received on the incoming status port. The function will be
  called with two arguments, the device number found in the packet,
  and the vector of unsigned byte values corresponding to the packet
  data."}
  packet-listeners (atom #{}))

(defn- process-packet
  "React to a packet that was sent to our player port."
  [packet data]
  ;; For now just stash the most recent packet and data into our state.
  (swap! state assoc :packet packet :data data)
  (let [device-number (get data 33)]
    (swap! state assoc-in [:device-data device-number] data)
    (when (seq @packet-listeners)
      (doseq [listener @packet-listeners]
        (try
          (listener device-number data)
          (catch Throwable t
            (timbre/warn t "Problem calling device packet listener")))))))

(defn add-packet-listener
  "Registers a function to be called whenever a packet is sent to the
  incoming status port. The function will be called with two
  arguments, the device number found in the packet, and the vector of
  unsigned byte values corresponding to the packet data."
  [listener]
  (swap! packet-listeners conj listener))

(defn remove-packet-listener
  "Stops calling a packet listener function that was registered with
  [[add-packet-listener]]."
  [listener]
  (swap! packet-listeners disj listener))

(def keep-alive-interval
  "How often, in milliseconds, we should send keep-alive packets to
  maintain our presence on the network."
  1500)

(def header-bytes
  "The constant bytes which always form the start of a packet that we
  send."
  [0x51 0x73 0x70 0x74 0x31 0x57 0x6d 0x4a 0x4f 0x4c])

(defn- send-packet
  "Create and send a packet with the specified `header-type` value at
  byte 10, and specified payload bytes following the device name."
  [header-type payload]
  (let [packet (byte-array (concat header-bytes [header-type 00] (:device-name @state) payload))
        datagram (DatagramPacket. packet (count packet) (:destination @state) finder/announce-port)]
    (.send (:socket @state) datagram)))

(defn send-direct-packet
  "Create and send a packet to port 50001 of the specified device, with
  the specified `header-type` value at byte 10, and specified payload
  bytes following our device name."
  [device-number header-type payload]
  (if-let [device (finder/device-given-number device-number)]
    (let [packet (byte-array (concat header-bytes [header-type] (:device-name @state) payload))
        datagram (DatagramPacket. packet (count packet) (:address device) 50001)]
    (.send (:socket @state) datagram))
    (throw (ex-info (str "No device found with number " device-number) {}))))

(defn set-sync
  "Turn the specified player's sync mode on or off."
  [device-number sync?]
  (let [us        (:player-number @state)
        sync-byte (if sync? 0x10 0x20)
        payload   [0x01 0x00 us 0x00 0x08 0x00 0x00 0x00 us 0x00 0x00 0x00 sync-byte]]
    (send-direct-packet device-number 0x2a payload)))

(defn appoint-master
  "Tell the specified player to take over the tempo master role."
  [device-number]
  (let [us        (:player-number @state)
        payload   [0x01 0x00 us 0x00 0x08 0x00 0x00 0x00 us 0x00 0x00 0x00 01]]
    (send-direct-packet device-number 0x2a payload)))

(defn- send-keep-alive
  "Send a packet which keeps us marked as present and active on the DJ
  Link network."
  []
  (let [{:keys [player-number mac-address ip-address]} @state]
    (try
      (send-packet 6 (concat [0x01 0x02 0x00 0x36 player-number 0x01] mac-address ip-address
                             [0x01 0x00 0x00 0x00 0x01 0x00]))
      (catch Exception e
        (timbre/error e "Unable to send keep-alive packet to DJ-Link announcement port, shutting down.")
        (shut-down)))))

(defn start
  "Create a virtual CDJ on the specified address and interface, with
  packet reception and keep-alive threads, and assign it a name and
  player number defaulting to \"Virtual CDJ\" and 5, but configurable
  via optional keyword arguments `:device-name` and `:player-number`."
  [interface address & {:keys [device-name player-number] :or {device-name "Virtual CDJ" player-number 5}}]
  (shut-down)
  (try
    (let [socket (DatagramSocket. incoming-port (.getAddress address))]
      (swap! state assoc
             :device-name (map byte (take 20 (concat device-name (repeat 0))))
             :player-number player-number
             :socket socket
             :ip-address (vec (map util/unsign (.getAddress (.getLocalAddress socket))))
             :mac-address (vec (map util/unsign (.getHardwareAddress interface)))
             :destination (.getBroadcast address)
             :watcher (future (loop []
                                (let [packet (receive socket)
                                      data (vec (map util/unsign (take (.getLength packet) (.getData packet))))]
                                  (process-packet packet data))
                                (recur)))
             :keep-alive (future (loop []
                                   (Thread/sleep keep-alive-interval)
                                   (send-keep-alive)
                                   (recur)))))

    (catch Exception e
      (timbre/error e "Failed while trying to set up virtual CDJ.")
      (shut-down))))
