(ns dysentery.finder
  "Watches for broadcast traffic to the port where devices announce
  their presence, so we can see the addresses (and therefore network
  interface) on which DJ Link activity is taking place."
  {:author "James Elliott"}
  (:require [clojure.math.numeric-tower :as math]
            [dysentery.util :as util]
            [taoensso.timbre :as timbre])
  (:import [java.net InetAddress DatagramPacket DatagramSocket NetworkInterface]))

(def announce-port
  "The UDP port on which device announcements are
  broadcast."
  50000)

(defonce ^{:private true
           :doc "Holds the persistent server socket, map of seen
  devices (keyed on a tuple of the address from which they transmit
  and the device number, since devices like the XDJ-X& implement
  multiple devices on the same IP address), and the future that
  processes packets."} state (atom {:socket nil
               :devices-seen {}
               :watcher nil}))

(defn shut-down
  "Close the UDP server socket and terminate the packet processing
  thread, if they are active."
  []
  (swap! state (fn [current]
                 (-> current
                     (update-in [:socket] #(when %
                                             (try (.close %)
                                                  (catch Exception e
                                                    (timbre/warn e "Problem closing DJ-Link announcement socket.")))
                                             nil))
                     (update-in [:watcher] #(when %
                                              (try (future-cancel %)
                                                   (catch Exception e
                                                     (timbre/warn e "Problem stopping DJ-Link announcement finder.")))
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
           (timbre/error e "Problem reading from DJ Link socket, shutting down.")
           (shut-down)))))

(def max-packet-interval
  "The number of milliseconds after which we will consider a device to
  have disappeared if we have not heard any packets from it."
  5000)

(defn- remove-stale-devices
  "Age out any DJ Link devices we have not heard from in too long."
  []
  (doseq [[k v] (:devices-seen @state)]
    (when (> (- (System/currentTimeMillis) (:last-seen v)) max-packet-interval)
      (swap! state update-in [:devices-seen] dissoc k))))

(defn- update-known-devices
  "When a packet has been received, if it looks like a Pro DJ Link
  packet, this function is called to note that the sender is a current
  candidate device."
  [packet data]
  ;; Record the newly-seen device
  (let [device-number (aget data 36)]
    (swap! state update-in [:devices-seen [(.getAddress packet) device-number]]
           (fn [device]
             {:packet-count (inc (:packet-count device 0))
              :last-seen (System/currentTimeMillis)
              :device {:name (.trim (String. data 12 20))
                       :player device-number
                       :address (.getAddress packet)}})))
  ;; Get MAC address at bytes 38-43?
  ;; TODO: Compare address with bytes 44-47
  (remove-stale-devices))

(defn start
  "Open UDP server socket and create the packet reception thread,
  discarding any former ones that might have existed."
  []
  (shut-down)
  (try
    (swap! state
           (fn [current]
             (let [socket (DatagramSocket. announce-port (InetAddress/getByName "0.0.0.0"))]
               (-> current
                   (assoc :socket socket)
                   (assoc :watcher
                          (future (loop []
                                    (let [packet (receive socket)
                                          data (.getData packet)]
                                      ;; Check packet length
                                      ;; TODO: Check header bytes, perhaps use Protobuf for this?
                                      (when (= 54 (.getLength packet))
                                        (update-known-devices packet data)))
                                    (recur))))))))
    (catch Exception e
      (timbre/warn e "Failed while trying to set up DJ-Link reception.")
      (shut-down))))

(defn start-if-needed
  "If there is not already a socket receiving DJ Link packets, set it
  up."
  []
  (when-not (:socket @state) (start)))

(defn current-dj-link-devices
  "Returns the set of DJ Link devices which are currently visible on the
  network. If `ignore-addresses` is supplied, any devices whose
  addresses are present in that set will be filtered out of the
  results (this allows the Virtual CDJ to avoid trying to talk to
  itself)."
  ([]
   (current-dj-link-devices #{}))
  ([ignore-addresses]
   (start-if-needed)
   (remove-stale-devices)
   (let [{:keys [devices-seen]} @state
         all-devices            (for [[_k v] devices-seen] (:device v))]
     (set (filter #(not (ignore-addresses (:address %))) all-devices)))))

(defn device-given-number
  "Returns what we know about the DJ Link device with the specified
  player number, if anything."
  [player]
  (first (filter #(= (:player %) player) (current-dj-link-devices))))

(defn address-to-int
  "Converts an Inet4Address to its integer equivalent."
  [address]
  (let [pieces (map util/unsign (.getAddress address))]
    (reduce (fn [acc n] (+ (* 256 acc) n)) 0 pieces)))

(defn same-network?
  "Tests whether two Inet4Address objects are on the same network,
  given a network prefix length (in bits)."
  [prefix-length address-1 address-2]
  (let [prefix-mask (bit-and 0xffffffff (bit-shift-left -1 (- 32 prefix-length)))]
    (= (bit-and (address-to-int address-1) prefix-mask)
       (bit-and (address-to-int address-2) prefix-mask))))

(defn find-interface-and-address-for-device
  "Given a device map (as returned by [[current-dj-link-devices]]),
  figure out the correct network interface and interface address to
  use to communicate with that device."
  [device]
  (loop [interfaces (enumeration-seq (NetworkInterface/getNetworkInterfaces))]
    (when-let [current (first interfaces)]
      (or (loop [addresses (.getInterfaceAddresses current)]
            (when-let [candidate (first addresses)]
              (or (when (and (some? (.getBroadcast candidate)) ; Only IPv4 addresses have broadcast addresses
                             (same-network? (.getNetworkPrefixLength candidate)
                                            (.getAddress candidate)
                                            (:address device)))
                    [current candidate]) ; We found the right NetworkInterface and InterfaceAddress, return them
                  (recur (rest addresses)))))
          (recur (rest interfaces))))))
