(ns dysentery.dbserver
  "Parses and builds the fields, messages, and workflows that support
  interacting with (or offering) a database server for track
  information."
  (require [dysentery.finder :as finder]
           [dysentery.util :as util])
  (:require [taoensso.timbre :as timbre]))

(defn send-bytes
  "Send a vector of byte values to an output stream."
  [os v]
  (println "Sending " (vec (map #(format "%02x" (util/unsign %)) v)))
  (let [obuf (byte-array (map util/make-byte v))]
    (.write os obuf 0 (count obuf))
    (.flush os)))

(defn recv-bytes
  "Receive a buffer of bytes from an input stream, returning a vector
  of the number read and the buffer."
  [is]
  (let [ibuf (byte-array 4096)
        len (.read is ibuf)]
    (print "recv[" len "]: ")
    (doseq [i (range len)]
      (print (format "%02x " (util/unsign (aget ibuf i)))))
    (println)
    [len ibuf]))

(defn number->bytes
  "Splits a number with the specified byte size into its individual
  bytes, returning them as a vector."
  [n size]
  (loop [result (list (bit-and n 0xff))
         n (bit-shift-right n 8)
         remaining (dec size)]
    (if (zero? remaining)
      (vec result)
      (recur (conj result (bit-and n 0xff))
             (bit-shift-right n 8)
             (dec remaining)))))

(defn bytes->number
  "Given a byte array buffer, the index of the first byte of a number
  value, the number of bytes that make it up, calculates the integer
  that the bytes represent."
  [buffer index size]
  (loop [i (inc index)
         left (dec size)
         result (util/unsign (aget buffer index))]
    (if (pos? left)
      (recur (inc i) (dec left) (+ (* result 256) (util/unsign (aget buffer i))))
      result)))

(defn number-field
  "Creates a field that represents a 4-byte number in a message. Can
  be created from just the number, the four bytes, or a buffer and
  index at which the field is found in an incoming network message.
  Returns nil and logs an error if parsing the network message fails."
  ([n]
   {:type         :number
    :arg-list-tag 0x06
    :bytes        (into [0x11] (number->bytes n 4))})
  ([buffer index]
   (cond
     (< (count buffer) (+ index))
     (timbre/error "buffer does not have enough remaining data to contain a number field")

     (not= (aget buffer index) 0x11)
     (timbre/error "Trying to read number field, found type tag" (aget buffer index) "when expecting 17.")

     :ele
     (number-field (bytes->number buffer (inc index) 4))))
  ([byte-1 byte-2 byte-3 byte-4]
   (number-field (byte-array [0x11 byte-1 byte-2 byte-3 byte-4]) 0)))

(defn disconnect
  "Closes a connection to a player. You can not use it after this
  point."
  [{:keys [input-stream output-stream socket]}]
  (.close input-stream)
  (.close output-stream)
  (.close socket))

(defn connect-to-player
  "Opens a database server connection to the specified player number,
  posing as the specified player number, which must be unused on the
  network. Returns a value that can be used with the other functions
  in this namespace to interact with the database server on that
  player.

  For simplicity, we go directly to port 1051 on the specified player,
  even though Beat Link already implements the correct mechanism of
  querying for the appropriate port. This is just quick and dirty
  hackery code to further our knowledge.

  Returns `nil` if the target player could not be found."
  [target-player-number pose-as-player-number]
  (when-let [device (finder/device-given-number target-player-number)]
    (let [sock     (java.net.Socket. (:address device) 1051)
          is       (.getInputStream sock)
          os       (.getOutputStream sock)
          player   {:socket        sock
                    :input-stream  is
                    :output-stream os}
          greeting (number-field 1)]  ; The greeting packet is a number field representing the number 1.
      (send-bytes os (:bytes greeting))
      (let [[_ response] (recv-bytes is)]
        (if (not= (number-field (byte-array response) 0) greeting)
          (do (disconnect player)
              (timbre/error "Did not receive expected greeting response from player, closed."))
          (do
            ;; TODO send setup message
            player))))))

(def message-start-marker
  "The number field which is always used to identify the start of a new
  message."
  (number-field 0x872349ae))

