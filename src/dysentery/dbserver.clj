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
  be created from just the number, or from the four individual bytes."
  ([n]
   {:type         :number
    :number       n
    :arg-list-tag 0x06
    :bytes        (into [0x11] (number->bytes n 4))})
  ([byte-1 byte-2 byte-3 byte-4]
   (number-field (bytes->number (byte-array [byte-1 byte-2 byte-3 byte-4]) 0 4))))

(defn message-type-field
  "Creates a 4-byte field which, as the third field in a message,
  identifies the type of the message, as well as the number of
  argument fields that it includes. The type is stored in the first
  two bytes of this field, followed by 0x0f, and the argument count."
  [message-type arg-count]
  {:type         :message-type
   :message-type message-type
   :arg-count    arg-count
   :bytes        (into [0x10] (concat (number->bytes message-type 2) [0x0f (bit-and arg-count 0xff)]))})

(defn blob-field
  "Creates a variable sized field containing bytes of data, prefixed
  by a 4-byte size."
  [data]
  {:type :blob
   :data (vec data)
   :bytes (into [0x14] (concat (number->bytes (count data) 4) data))})

;; TODO: Add string-field and a read-field implementation for it

;; TODO: These have to take the player object as well, so they can
;; read more data if needed, and return the buffer and offset where
;; reading of the next field should begin.

(defmulti read-field
  "Read a tagged field value from the incoming network buffer. If
  there is a parsing problem, an explanatory error is logged, and
  `nil` is returned."
  (fn [buffer index]
    (when (< index (count buffer))
      (aget buffer index))))

(defmethod read-field nil  ; No data available
  [buffer index]
  (timbre/error "Cannot read field at end of buffer."))

(defmethod read-field 0x11  ; A number field
  [buffer index]
  (if (< (count buffer) (+ index 5))
    (timbre/error "Buffer does not have enough remaining data to contain a number field.")
    (number-field (bytes->number buffer (inc index) 4))))

(defmethod read-field 0x10  ; A message type field
  [buffer index]
  (cond
     (< (count buffer) (+ index 5))
     (timbre/error "Buffer does not have enough remaining data to contain a message type field.")

     (not= (aget buffer (+ index 3)) 0x0f)
     (timbre/error "Message type field does not have value 15 following message type, found" (aget buffer (+ index 2)))

     :else
     (message-type-field (bytes->number buffer (inc index) 2) (aget buffer (+ index 4)))))

(defmethod read-field 0x14  ; A blob field
  [buffer index]
  (if (< (count buffer) (+ index 5))
    (timbre/error "Buffer does not have enough remaining data to contain a blob field.")
    (let [size (bytes->number buffer (inc index) 4)]
      (if (< (count buffer) (+ index 5 size))
        (timbre/error "Buffer does not have enough remaining data for blob of size" size)
        (blob-field (take size (drop 5 buffer)))))))

(defmethod read-field :default  ; An unrecognized field
  [buffer index]
  (timbre/error "Do not know what kind of field corresponds to type tag" (aget buffer index)))

(def message-start-marker
  "The number field which is always used to identify the start of a new
  message."
  (number-field 0x872349ae))

(defn build-message
  "Puts together the fields that make up a message, with the specified
  transaction number, message type, and argument fields."
  [transaction-number message-type & args]
  (let [fields (concat [message-start-marker (number-field transaction-number)
                        (message-type-field message-type (count args))
                        (blob-field (take 12 (concat (map :arg-list-tag args) (repeat 0))))]
                       args)]
    (vec (apply concat (map :bytes fields)))))

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
        (if (not= (read-field (byte-array response) 0) greeting)
          (do (disconnect player)
              (timbre/error "Did not receive expected greeting response from player, closed."))
          (do
            ;; Send the setup message for our player number
            (send-bytes os (build-message 0xfffffffe 0 (number-field pose-as-player-number)))
            (recv-bytes is)
            player))))))

