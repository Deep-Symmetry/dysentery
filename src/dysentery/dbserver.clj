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
  #_(println "Sending " (vec (map #(format "%02x" (util/unsign %)) v)))
  (let [obuf (byte-array (map util/make-byte v))]
    (.write os obuf 0 (count obuf))
    (.flush os)))

(defn recv-bytes
  "Receive the specified number of bytes from an input stream,
  returning a vector of bytes read when successful, or nil if the
  socket was closed or the read timed out."
  [is size]
  (let [ibuf (byte-array size)]
    (try
      (loop [offset 0
             len    (.read is ibuf offset (- size offset))]
        (let [offset (+ offset len)]
          (cond (neg? len)
                (timbre/warn "Socket closed trying to read" size "bytes from dbserver port.")

                (= offset size)
                (let [result (vec ibuf)]
                  #_(print "recv[" size "]: ")
                  #_(doseq [b result]
                    (print (format "%02x " (util/unsign b))))
                  #_(println)
                  result)

                :else
                (recur offset (.read is ibuf offset (- size offset))))))
      (catch java.net.SocketTimeoutException e
        (timbre/warn "Timed out trying to read" size "bytes from dbserver port.")))))

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
  "Given a byte vector, calculates the integer that the bytes
  represent."
  [bytes]
  (loop [left (rest bytes)
         result (util/unsign (first bytes))]
    (if (seq left)
      (recur (rest left) (+ (* result 256) (util/unsign (first left))))
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
   (number-field (bytes->number [byte-1 byte-2 byte-3 byte-4]))))

(defn message-type-field
  "Creates a 4-byte field which, as the third field in a message,
  identifies the type of the message, as well as the number of
  argument fields that it includes. The type is stored in the first
  two bytes of this field, followed by 0x0f, and the argument count."
  [message-type argument-count]
  {:type           :message-type
   :message-type   message-type
   :argument-count argument-count
   :bytes          (into [0x10] (concat (number->bytes message-type 2) [0x0f (bit-and argument-count 0xff)]))})

(defn blob-field
  "Creates a variable sized field containing bytes of data, prefixed
  by a 4-byte size."
  [data]
  {:type :blob
   :data (vec data)
   :arg-list-tag 0x03
   :bytes (into [0x14] (concat (number->bytes (count data) 4) data))})

(defn string-field
  "Creates a variable sized field containing a string, prefixed
  by a 4-byte size."
  [text]
  (let [bytes (.getBytes text "UTF-16BE")]
    {:type   :string
     :string text
     :arg-list-tag 0x02
     :bytes  (into [0x26] (concat (number->bytes (count bytes) 4) bytes))}))

;; TODO: Add string-field and a read-field implementation for it

;; TODO: These have to take the player object as well, so they can
;; read more data if needed, and return the buffer and offset where
;; reading of the next field should begin.

(defmulti read-field
  "Read a tagged field value from the input stream. If there is a
  communication or parsing problem, an explanatory error is logged,
  and `nil` is returned. The multimethod dispatches on the first byte
  read, which is the tag that identifies the type of the field."
  (fn [is]
    (when-let [tag (recv-bytes is 1)]
      (util/unsign (first tag)))))

(defmethod read-field nil  ; No data available
  [is]
  (timbre/error "Attempt to read field failed."))

(defmethod read-field 0x11  ; A number field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (number-field (bytes->number bytes))
    (timbre/error "Attempt to read number field failed.")))

(defmethod read-field 0x10  ; A message type field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (if (not= (get bytes 2) 0x0f)
      (timbre/error "Message type field does not have value 15 following message type, found" (get bytes 2))
      (message-type-field (bytes->number (take 2 bytes)) (util/unsign (get bytes 3))))
    (timbre/error "Attempt to read message type field failed.")))

(defmethod read-field 0x14  ; A blob field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (let [size (bytes->number bytes)]
      (if-let [body (recv-bytes is size)]
        (blob-field body)
        (timbre/error "Failed to read" size "bytes of blob field.")))
    (timbre/error "Attempt to read size of blob field failed.")))

(defmethod read-field 0x26  ; A string field
  [is]
  (if-let [bytes (recv-bytes is 4)]
    (let [size (bytes->number bytes)]
      (if-let [body (recv-bytes is (* 2 size))]
        (string-field (String. (byte-array body) 0 (* 2 (dec size)) "UTF-16BE"))
        (timbre/error "Failed to read" (* 2 size) "bytes of string field.")))
    (timbre/error "Attempt to read size of string field failed.")))


(defmethod read-field :default  ; An unrecognized field
  [is]
  (timbre/error "Unknown tag for field."))

(def message-start-marker
  "The number field which is always used to identify the start of a new
  message."
  (number-field 0x872349ae))

(defn build-message
  "Puts together the fields that make up a message, with the specified
  transaction number, message type, and argument fields, as a map with
  the same structure returned by `read-message`."
  [transaction-number message-type & args]
  {:start          message-start-marker
   :transaction    (number-field transaction-number)
   :message-type   (message-type-field message-type (count args))
   :argument-types (blob-field (take 12 (concat (map :arg-list-tag args) (repeat 0))))
   :arguments      args})

(defn build-setup-message
  "Creates a message that can be used to set up communication with a
  player, given the player number we are pretending to be. This is a
  message with transaction ID `0xfffffffe` and a message type of 0,
  whose sole argument is a number field containing our player number."
  [player-number]
  (build-message 0xfffffffe 0 (number-field player-number)))

(defn send-message
  "Sends the bytes that make up a message to the specified player."
  [player message]
  (let [fields (concat (reduce #(conj %1 (message %2)) [] [:start :transaction :message-type :argument-types])
                       (:arguments message))]
    (send-bytes (:output-stream player) (vec (apply concat (map :bytes fields))))))

(defn- read-arguments
  "Reads the argument fields which end a message, validating that
  their types match what was decleared in the argument-types field,
  returning the completed message map on success, or `nil` on
  failure."
  [is message]
  (let [expected (get-in message [:message-type :argument-count])]
    (if (> expected 12)
      (timbre/error "Can't read a message with more than twelve arguments.")
      (if (= (count (:arguments message)) expected)
        message
        (if-let [arg (read-field is)]
          (let [next-tag (util/unsign (get-in message [:argument-types :data (count (:arguments message))]))]
            (if (= (:arg-list-tag arg) next-tag)
              (recur is (update message :arguments conj arg))
              (timbre/error "Found argument of wrong type when trying to read a message. Expected tag:"
                            next-tag "and found:" (:arg-list-tag arg))))
          (timbre/error "Unable to read an argument field when trying to read a message."))))))

(defn- read-argument-types
  "Reads the fourth field of a message, which should be a blob listing
  the argument types, and continues building the message map if
  successful, or returns `nil` on failure."
  [is message]
  (if-let [argument-types (read-field is)]
    (if (= :blob (:type argument-types))
      (read-arguments is (assoc message :argument-types argument-types))
      (timbre/error "Did not find argument-type blob field when trying to read a message."))
    (timbre/error "Unable to read argument-type blob field when trying to read a message.")))

(defn- read-message-type
  "Reads the third field of a message, which should be a special
  message-type field, and continues building the message map if
  successful, or returns `nil` on failure."
  [is message]
  (if-let [message-type (read-field is)]
    (if (= :message-type (:type message-type))
      (read-argument-types is (assoc message :message-type message-type))
      (timbre/error "Did not find message-type field when trying to read a message."))
    (timbre/error "Unable to read message-type field when trying to read a message.")))

(defn- read-transaction-id
  "Reads the second field of a message, which should be a number, and
  continues building the message map if successful, or returns `nil`
  on failure."
  [is message]
  (if-let [tx-id (read-field is)]
    (if (= :number (:type tx-id))
      (read-message-type is (assoc message :transaction tx-id))
      (timbre/error "Did not find numeric transaction ID field when trying to read a message."))
    (timbre/error "Unable to read transaction ID field when trying to read a message.")))

(defn read-message
  "Attempts to read a message from the specified player. Returns
  a map breaking down the fields that make up the message, or `nil` if
  a problem was encountered."
  [player]
  (let [is (:input-stream player)]
    (if-let [start (read-field is)]
      (if (= start message-start-marker)
        (read-transaction-id is {:start start
                                 :arguments []})
        (timbre/error "Did not find message start marker when trying to read a message."))
      (timbre/error "Unable to read first field of message."))))

(def item-type-labels
  "The map from a menu item type value to the corresponding label."
  {0x01 "Folder"
   0x02 "Album Title"
   0x03 "Disc"
   0x04 "Track Title"
   0x06 "Genre"
   0x07 "Artist"
   0x0a "Rating"
   0x0b "Duration (s)"
   0x0d "Tempo"
   0x0f "Key"
   0x13 "Color"
   0x23 "Comment"
   0x2e "Date Added"})

(defn describe-item-type
  "Given a number field, holding a menu item type renders a
  description of what it means."
  [type-field]
  (str "item type: " (get item-type-labels (:number type-field) "unknown")))

(def known-messages
  "The purpose and argument descriptions for message types that we
  know something about."
  {0x0001 {:type "invalid data"}
   0x1000 {:type      "load root menu"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "sort order"
                       "magic constant?"]}
   0x2002 {:type      "request track metadata"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "rekordbox ID"]}
   0x2003 {:type      "request album art"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "rekordbox ID"]}
   0x2004 {:type      "request track waveform summary"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "rekordbox ID"]}
   0x2104 {:type      "request track cue points"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "rekordbox ID"]}
   0x2202 {:type      "request CD track data"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "track number"]}
   0x2204 {:type      "request beat grid information"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "rekordbox ID"]}
   0x2904 {:type      "request track waveform detail"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "rekordbox ID"]}
   0x3000 {:type      "render menu"
           :arguments ["requesting player, menu (1), media, analyzed (1)"
                       "offset"
                       "limit"
                       "unknown (0)?"
                       "len_a (= limit)?"
                       "unknown (0)?"]}
   0x3e03 {:type "request USB information"} ; TODO: See if this can be used to work around all-players-in-use!!!
   0x4000 {:type      "requested data available"
           :arguments ["request type"
                       "number of items available"]}
   0x4001 {:type "rendered menu header"}
   0x4002 {:type      "album art"
           :arguments ["request type"
                       "constant 0?"
                       "image length"
                       "image bytes"]}
   0x4101 {:type      "rendered menu item"
           :arguments ["numeric 1"
                       "numeric 2"
                       "label 1 byte size"
                       "label 1"
                       "label 2 byte size 2"
                       "label 2"
                       describe-item-type
                       "column configuration?"
                       "album art id"]}
   0x4201 {:type "rendered menu footer"}
   0x4402 {:type      "waveform summary"
           :arguments ["request type"
                       "constant 0?"
                       "image length"
                       "image bytes"]}
   0x4602 {:type      "beat grid"
           :arguments ["request type"
                       "constant 0?"
                       "image length"
                       "image bytes"
                       "constant 0?"]}
   0x4702 {:type      "cue points"
           :arguments ["request type"
                       "unknown"
                       "blob 1 length"
                       "blob 1"
                       "constant 0x24?"
                       "unknown"
                       "unknown"
                       "blob 2 length"
                       "blob 2"]}
   0x4a02 {:type      "waveform detail"
           :arguments ["request type"
                       "constant 0?"
                       "image length"
                       "image bytes"]}})

(defn get-message-type
  "Extracts the message type tag from a message structure."
  [message]
  (get-in message [:message-type :message-type]))

(defn describe-message
  "Summarizes the salient information about a message."
  [message]
  (when message
    (let [message-type (get-message-type message)
          description (get known-messages message-type)]
      (println (str "Transaction: " (get-in message [:transaction :number])
                    ", message type: " (format "0x%04x (%s)" message-type (:type description "unknown"))
                    ", argument count: " (get-in message [:message-type :argument-count])
                    ", arguments:"))
      (doall
       (map-indexed
        (fn [i arg]
          (print (case (:type arg)
                   :number (format "  number: %10d (0x%08x)" (:number arg) (:number arg))
                   :blob   (str "  blob: " (clojure.string/join " " (map #(format "%02x" %) arg)))
                   :string (str "  string: \"" (:string arg) "\"")
                   (str "unknown: " arg)))
          (let [description (get-in description [:arguments i] "unknown")
                resolved (if (ifn? description) (description arg) description)]
            (println (str " [" resolved "]"))))
        (:arguments message)))))
  nil)

(defn read-menu-responses
  "After a menu setup query (which is also used for things like track
  metadata) has returned a successful response including the number of
  items available, this requests all of the items, gathering the
  corresponding messages."
  [player menu-field item-count]
  (let [id (swap! (:counter player) inc)
        zero-field (number-field 0)
        count-field (number-field item-count)
        request (build-message id 0x3000 menu-field zero-field count-field zero-field count-field zero-field)]
    (print "Sending > ")
    (describe-message request)
    (send-message player request)
    (loop [i 1
           result []]
      (if-let [response (read-message player)]
        (do (print "Received" i " > ")
            (describe-message response)
            (let [message-type (get-message-type response)]
              (if (= message-type 0x4201)  ; Footer means we are done
                result
                (recur (inc i) (if (= message-type 0x4101) (conj result response) result)))))
        (do (timbre/error "Unable to read menu footer, returning partial result")
            result)))))

(defn request-metadata
  "Sends the sequence of messages that request the metadata for a
  track in a media slot on the player."
  [player slot track]
  (let [id (swap! (:counter player) inc)
        menu-field (number-field (:number player) 1 slot 1)
        setup (build-message id 0x2002 menu-field (number-field track))]
    (print "Sending > ")
    (describe-message setup)
    (send-message player setup)
    (when-let [response (read-message player)]
      (print "Received > ")
      (describe-message response)
      (when (= 0x4000 (get-message-type response))
        (let [metadata (read-menu-responses player menu-field (get-in response [:arguments 1 :number]))]
          ;; TODO build and return more compact structure.
          )))))

(defn disconnect
  "Closes a connection to a player. You can not use it after this
  point."
  [{:keys [input-stream output-stream socket]}]
  (.close input-stream)
  (.close output-stream)
  (.close socket))

(def connect-timeout
  "The number of milliseconds after which we should give up attempting
  to connect to the dbserver socket."
  5000)

(def read-timeout
  "The numbe of milliseconds after which we should give up waiting for
  a response while communicating with a connected dbserver."
  2000)

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
    (let [sock     (java.net.Socket.)
          _        (.connect sock (java.net.InetSocketAddress. (:address device) 1051) connect-timeout)
          is       (.getInputStream sock)
          os       (.getOutputStream sock)
          player   {:socket        sock
                    :input-stream  is
                    :output-stream os
                    :number        pose-as-player-number
                    :counter       (atom 0)}
          greeting (number-field 1)]  ; The greeting packet is a number field representing the number 1.
      (.setSoTimeout sock read-timeout)
      (send-bytes os (:bytes greeting))
      (if (not= (read-field is) greeting)
        (do (disconnect player)
            (timbre/error "Did not receive expected greeting response from player, closed."))
        (do
          (send-message player (build-setup-message pose-as-player-number))
          (describe-message (read-message player))
          player)))))

