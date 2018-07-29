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

(defn stop-sending-status
  "Shut down the thread which is sending status packets to all players
  on the network."
  []
  (swap! state update :status-sender #(when %
                                        (try (future-cancel %)
                                             (catch Exception e
                                               (timbre/warn e "Problem stopping DJ-Link player status sender.")))
                                        nil)))

(defn shut-down
  "Close the UDP server socket and terminate the packet processing
  thread, if they are active."
  []
  (stop-sending-status)
  (swap! state (fn [current]
                 (-> current
                     (update :socket #(when %
                                        (try (.close %)
                                             (catch Exception e
                                               (timbre/warn e "Problem closing DJ-Link player socket.")))
                                        nil))
                     (update :watcher #(when %
                                         (try (future-cancel %)
                                              (catch Exception e
                                                (timbre/warn e "Problem stopping DJ-Link player receiver.")))
                                         nil))
                     (update :keep-alive #(when %
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

(def status-interval
  "How often, in milliseconds, should we send status packets to the
  other devices on the network."
  200)

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
  bytes following our device name. `device` can either be a device
  number to be looked up, or an actual device details map. Packets can
  be sent to a different port (e.g. 50002 for status packets) by
  specifying the port number as an optional fourth argument."
  ([device header-type payload]
   (send-direct-packet device header-type payload 50001))
  ([device header-type payload port]
   (if-let [device (if (number? device) (finder/device-given-number device) device)]
     (let [data     (vec (concat header-bytes [header-type] (:device-name @state) payload))
           packet   (byte-array data)
           datagram (DatagramPacket. packet (count packet) (:address device) port)]
       (.send (:socket @state) datagram)
       (when (= port 50002)
         (require 'dysentery.view)
         ((resolve 'dysentery.view/handle-device-packet) port 0 data)))
     (throw (ex-info (str "No device found with number " device) {})))))

(defn set-player-sync
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

(defn send-fader-start
  "Send a message which will start or stop a set of players. The
  arguments are sets of player numbers to start and stop; if you try
  to both start and stop the same player, only the stop will be sent."
  [start stop]
  (let [us       (:player-number @state)
        commands (for [player (map inc (range 4))]
                   (if (stop player)
                     0x01
                     (if (start player)
                       0x00
                       0x02)))
        payload  (concat [0x01 0x00 us 0x00 0x04] commands)]
    (doseq [device-number (clojure.set/union start stop)]
      (send-direct-packet device-number 0x02 payload))))

(defn- build-status-payload
  "Constructs the bytes which follow the device name in a status packet
  describing our current state."
  []
  (swap! state update :packet-count (fnil inc 0))
  (let [{:keys [player-number playing? master? master-yielding-to sync? tempo beat sync-n packet-count]} @state

        tempo (math/round (* tempo 100))
        a     (if playing? 0x01 0x00)
        b-b   (util/make-byte (inc (mod (dec beat) 4)))
        d-r   player-number
        f     (+ 0x84
                 (if playing? 0x40 0)
                 (if master? 0x20 0)
                 (if sync? 0x10 0))
        p-1   (if playing? 3 5)
        p-2   (if playing? 0x7a 0x7e)
        p-3   (if playing? 9 1)
        s-r   3 #_0x02  ; TODO: Can we change this back to a local non-rekordbox track?
        t-r   0x01
        ;; TODO: The byte following this one changes from 0xff to the device number we are handing off to for
        ;;       a single message during master change, it seems. Investigate other traffic around this point.
        m     (if master? 1 0)
        y     (or master-yielding-to 0xff)]
    (concat [0x01
             0x04 player-number 0x00 0xf8 player-number 0x00 0x01 a  ; 0x20
             d-r  s-r  t-r  0x00 0x00 0x00 0x00 0x0d  ; 0x28
             0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x00  ; 0x30
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x38
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x40
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x48
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x50
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x58
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x60
             0x01 0x00 0x04 0x04 0x00 0x00 0x00 0x04  ; 0x68
             0x00 0x00 0x00 0x04 0x00 0x01 0x00 0x00  ; 0x70
             0x00 0x00 0x00 p-1  0x31 0x2e 0x34 0x30  ; 0x78
             0x00 0x00 0x00 0x00]                     ; 0x80
            (util/decompose-int sync-n 4)             ; 0x84
            [0x00 f    0xff p-2  0x00 0x10 0x00 0x00  ; 0x88
             0x80 0x00] (util/decompose-int tempo 2)  ; 0x90
            [0x7f 0xff 0xff 0xff]                     ; 0x94
            [0x00 0x10 0x00 0x00 0x00 p-3  m    y]    ; 0x98
            (util/decompose-int beat 4)               ; 0xa0
            [0x01 0xff b-b 0x00]                      ; 0xa4
            [0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0xa8
             0x00 0x00 0x00 0x00 0x00 0x00 0x01 0x00  ; 0xb0
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0xb8
             0x00 0x10 0x00 0x00 0x00 0x10 0x00 0x00] ; 0xc0
            (util/decompose-int packet-count 4)       ; 0xc8
            [0x0f 0x01 0x00 0x00                      ; 0xcc
             0x12 0x34 0x56 0x78 0x00 0x00 0x00 0x01  ; 0xd0  ; TODO: Can we shorten this back up?
             0x01 0x01 0x01 0x01 0x00 0x00 0x00 0x00  ; 0xd8
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0xe0
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0xe8
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0xf0
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0xf8
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x100
             0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 0x108
             0x00 0x00 0x00 0x15 0x00 0x00 0x07 0x61  ; 0x110
             0x00 0x00 0x06 0x2f])))                  ; 0x118

(defn- send-status
  "Sends a status packet reporting our current state directly to all
  players on the network."
  []
  (let [payload (build-status-payload)]
    (doseq [target (finder/current-dj-link-devices #{(.getLocalAddress (:socket @state))})]
      (try
        (send-direct-packet target 0x0a payload 50002)
        (catch Exception e
          (timbre/error e "Problem sending status packet to" target))))))

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

(defn start-sending-status
  "Starts the thread that sends status updates to all players on the
  network, if it is not already running."
  []
  (swap! state update :status-sender
         (fn [sender] (or sender
                          (future (loop []
                                    (send-status)
                                    (Thread/sleep status-interval)
                                    (recur)))))))

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
                                      data   (vec (map util/unsign (take (.getLength packet) (.getData packet))))]
                                  (process-packet packet data))
                                (recur)))
             :keep-alive (future (loop []
                                   (Thread/sleep keep-alive-interval)
                                   (send-keep-alive)
                                   (recur)))
             :tempo 120.0
             :beat 1
             :master? false
             :playing? false
             :sync? false
             :sync-n 0))

    (catch Exception e
      (timbre/error e "Failed while trying to set up virtual CDJ.")
      (shut-down))))

(defn yield-master-to
  "Called when we have been told that another player is becoming master
  so we should give up that role. Start announcing that we are
  yielding master to that player."
  [player]
  (when (not= player (:player-number @state))
    (swap! state assoc :master-yielding-to player)))

(defn master-yield-response
  "Called when a player we have told to yield the master role to us has
  responded. If the answer byte is non-zero, we are becoming the
  master. The final handoff will take place using the master and yield
  bytes in the status packets of both devices."
  [answer from-player]
  (timbre/info "Received master yield response" answer "from player" from-player)
  (when (not= 1 answer)
    (timbre/warn "Strange, received master yield packet with answer of" answer "from player" from-player))
  (swap! state assoc :master-yielded-from from-player))  ; Note we expect status announcing our master state now

(defn set-sync-mode
  "Change our sync mode; we will be synced if `sync?` is truthy."
  [sync?]
  (swap! state assoc :sync? (boolean sync?)))

(defn saw-master-packet
  "Record the current notion of the master player based on the device
  number found in a packet that identifies itself as the master. If
  `yielding` is not `0xff` this master is handing off its role to the
  specified player number. If it is us, it is finally time for us to
  really become master. Assert that state, and the yielding player
  should respond by dropping their master state, and setting their
  `sync-n` value to one greater than any other seen on the network.
  Also keep track of the highest sync-n value seen for any master
  packet on the network."
  [player sync-n yielding]
  (when (not= player (:player-number @state))  ; Ignore packets we are sending
    (when sync-n
      (swap! state update :max-sync-n #(max sync-n (or % 0))))
    (if (= yielding 0xff)
      ;; This is a normal, non-yielding master packet. Update our notion of the current master, and if we
      ;; were yielding, finish that process, and update our sync-n appropriately. If we were master and not
      ;; yielding, still give up our master state but log a warning at this unexpected situation.
      (let [{:keys [max-sync-n master-number master? master-yielding-to]} @state]
        (when master?
          (if master-yielding-to
            (if (= player master-yielding-to)
              (swap! state assoc :sync-n (inc max-sync-n))
              (timbre/warn "Expected to yield to player" master-yielding-to "but saw master asserted by player" player))
            (timbre/warn "Saw master asserted by player" player "when we were not yielding")))
        (swap! state (fn [current]
                       (-> current
                           (assoc :master? false :master-number player)
                           (dissoc :master-yielding-to)))))
      ;; This is a yielding master packet. If it is us that is being yielded to, take over master if we are expecting
      ;; to, otherwise log a warning.
      (let [{:keys [player-number master-yielded-from]} @state]
        (when (= player-number yielding)
          (when (not= player master-yielded-from)
            (timbre/warn "Expected player" master-yielded-from "to yield master to us, but player" player "did"))
          (swap! state (fn [current]
                         (-> current
                             (assoc :master? true :master-number player-number)
                             (dissoc :master-yielded-from)))))))))

(defn become-master
  "Attempt to become the tempo master by sending a command to the
  existing tempo master telling it to yield to us. We will change
  state upon receiving an proper acknowledgement message."
  []
  (let [us      (:player-number @state)
        master  (:master-number @state)
        payload [0x01 0x00 us 0x00 0x04 0x00 0x00 0x00 us]]
    (if master
      (do
        (timbre/info "Sending master yield packet to" master "payload:" payload)
        (send-direct-packet master 0x26 payload))
      (do
        (timbre/info "No current master; simply becoming it")
        (swap! state assoc :master? true :master-number us)))))

(defn set-tempo
  "Set our internal notion of tempo, for use when we are master."
  [tempo]
  (swap! state assoc :tempo tempo))
