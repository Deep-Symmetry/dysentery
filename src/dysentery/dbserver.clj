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
  {:pre [(pos? size)]}
  (loop [result (list (bit-and n 0xff))
         n (bit-shift-right n 8)
         remaining (dec size)]
    (if (zero? remaining)
      (vec result)
      (recur (conj result (bit-and n 0xff))
             (bit-shift-right n 8)
             (dec remaining)))))

(defn bytes->number
  "Given a byte sequence in big-endian order, calculates the integer
  that the bytes represent."
  [bytes]
  (loop [left (rest bytes)
         result (util/unsign (first bytes))]
    (if (seq left)
      (recur (rest left) (+ (* result 256) (util/unsign (first left))))
      result)))

(defn number-field
  "Creates a field that represents a 1, 2, or 4-byte number in a
  message. Can be created from the value and length, or from a
  sequence of the individual bytes."
  ([n size]
   (number-field (number->bytes n size)))
  ([bytes]
   (let [type-tag (case (count bytes)
                    1 0x0f
                    2 0x10
                    4 0x11)]
     {:type         :number
      :number       (bytes->number bytes)
      :arg-list-tag 0x06
      :bytes        (into [type-tag] bytes)})))

(defn blob-field
  "Creates a variable sized field containing bytes of data, prefixed
  by a 4-byte size."
  [data]
  {:type :blob
   :data (vec data)
   :arg-list-tag 0x03
   :bytes (when (pos? (count data)) (into [0x14] (concat (number->bytes (count data) 4) data)))})

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

(defn- read-number-field
  "Shared implementation for reading number fields once we know what
  size to expect."
  [is size]
  (if-let [bytes (recv-bytes is size)]
    (number-field (bytes->number bytes) size)
    (timbre/error "Attempt to read" size "byte number field failed.")))

(defmethod read-field 0x0f  ; A 1-byte number field
  [is]
  (read-number-field is 1))

(defmethod read-field 0x10  ; A 2-byte number field
  [is]
  (read-number-field is 2))

(defmethod read-field 0x11  ; A 4-byte number field
  [is]
  (read-number-field is 4))

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
  (number-field 0x872349ae 4))

(defn build-message
  "Puts together the fields that make up a message, with the specified
  transaction number, message type, argument-type, and argument
  fields, as a map with the same structure returned by
  `read-message`."
  [transaction-number message-type & args]
  {:start          message-start-marker
   :transaction    (number-field transaction-number 4)
   :message-type   (number-field message-type 2)
   :argument-count (number-field (count args) 1)
   :argument-types (blob-field (take 12 (concat (map :arg-list-tag args) (repeat 0))))
   :arguments      args})

(defn build-setup-message
  "Creates a message that can be used to set up communication with a
  player, given the player number we are pretending to be. This is a
  message with transaction ID `0xfffffffe` and a message type of 0,
  whose sole argument is a number field containing our player number."
  [player-number]
  (build-message 0xfffffffe 0 (number-field player-number 4)))

(defn send-message
  "Sends the bytes that make up a message to the specified player."
  [player message]
  (let [fields (concat (reduce #(conj %1 (message %2))
                               []
                               [:start :transaction :message-type :argument-count :argument-types])
                       (:arguments message))]
    (send-bytes (:output-stream player) (vec (apply concat (map :bytes fields))))))

(defn- read-arguments
  "Reads the argument fields which end a message, validating that
  their types match what was decleared in the argument-types field,
  returning the completed message map on success, or `nil` on
  failure."
  [is message]
  (let [expected (get-in message [:argument-count :number])]
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
  "Reads the fifth field of a message, which should be a blob listing
  the argument types, and continues building the message map if
  successful, or returns `nil` on failure."
  [is message]
  (if-let [argument-types (read-field is)]
    (if (= :blob (:type argument-types))
      (read-arguments is (assoc message :argument-types argument-types))
      (timbre/error "Did not find argument-type blob field when trying to read a message."))
    (timbre/error "Unable to read argument-type blob field when trying to read a message.")))

(defn- read-argument-count
  "Reads the fourth field of a message, which should be a one-byte
  number field, and continues building the message map if successful,
  or returns `nil` on failure."
  [is message]
  (if-let [argument-count (read-field is)]
    (if (and (= :number (:type argument-count)) (= 2 (count (:bytes argument-count))))
      (read-argument-types is (assoc message :argument-count argument-count))
      (timbre/error "Did not find argument-count field when trying to read a message."))
    (timbre/error "Unable to read argument-count field when trying to read a message.")))

(defn- read-message-type
  "Reads the third field of a message, which should be a two-byte
  number field, and continues building the message map if successful,
  or returns `nil` on failure."
  [is message]
  (if-let [message-type (read-field is)]
    (if (and (= :number (:type message-type)) (= 3 (count (:bytes message-type))))
      (read-argument-count is (assoc message :message-type message-type))
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
  {0x01  "Folder"
   0x02  "Album Title"
   0x03  "Disc"
   0x04  "Track Title"
   0x06  "Genre"
   0x07  "Artist"
   0x08  "Playlist"
   0x0a  "Rating"
   0x0b  "Duration (s)"
   0x0d  "Tempo"
   0x0f  "Key"
   0x13  "Color"
   0x23  "Comment"
   0x2e  "Date Added"
   0x204 "Track List entry by album"
   0x604 "Track List entry by genre"
   0x704 "Track List entry"
   0xd04 "Track List entry by BPM"
   0x2304 "Track List entry by comment"
   0x2904 "Playlist entry by track title"})

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
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "sort order"
                       "magic constant?"]}
   0x1002 {:type "request artist list"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "sort order?"]}
   0x1004 {:type "request track list"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "sort order?"]}
   0x1105 {:type      "request playlist or playlist folder"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "unknown"
                       "playlist or folder ID"
                       "0=playlist, 1=folder"]}
   0x2002 {:type      "request track metadata"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "rekordbox ID"]}
   0x2003 {:type      "request album art"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "art ID"]}
   0x2004 {:type      "request track waveform preview"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "unknown (4)"
                       "rekordbox ID"
                       "unknown (0)"
                       "required declared but missing blob"]}
   0x2104 {:type      "request track cue points"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "rekordbox ID"]}
   0x2202 {:type      "request CD track data"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "track number"]}
   0x2204 {:type      "request beat grid information"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "rekordbox ID"]}
   0x2904 {:type      "request track waveform detail"
           :arguments ["requesting player, for menu, media, analyzed (1)"
                       "rekordbox ID"]}
   0x3000 {:type      "render menu"
           :arguments ["requesting player, for menu, media, analyzed (1)"
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
   0x4003 {:type      "requested media not available"
           :arguments ["request type"]}
   0x4101 {:type      "rendered menu item"
           :arguments ["numeric 1 (parent id, e.g. artist for track)"
                       "numeric 2 (this id)"
                       "label 1 byte size"
                       "label 1"
                       "label 2 byte size"
                       "label 2"
                       describe-item-type
                       "column configuration? byte 3 is 1 when track played."
                       "album art id"
                       "playlist position"]}
   0x4201 {:type "rendered menu footer"}
   0x4402 {:type      "waveform preview"
           :arguments ["request type"
                       "constant 0?"
                       "waveform length"
                       "waveform bytes"]}
   0x4602 {:type      "beat grid"
           :arguments ["request type"
                       "constant 0?"
                       "beat grid length"
                       "beat grid bytes"
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
                       "waveform length"
                       "waveform bytes"]}})

(defn get-message-type
  "Extracts the message type value from a message structure."
  [message]
  (get-in message [:message-type :number]))

(defn describe-message
  "Summarizes the salient information about a message."
  [message]
  (when message
    (let [message-type (get-message-type message)
          description (get known-messages message-type)]
      (println (str "Transaction: " (get-in message [:transaction :number])
                    ", message type: " (format "0x%04x (%s)" message-type (:type description "unknown"))
                    ", argument count: " (get-in message [:argument-count :number])
                    ", arguments:"))
      (doall
       (map-indexed
        (fn [i arg]
          (print (case (:type arg)
                   :number (format "  number: %10d (0x%08x)" (:number arg) (:number arg))
                   :blob   (str "  blob: " (clojure.string/join " " (map #(format "%02x" %) (:data arg))))
                   :string (str "  string: \"" (:string arg) "\"")
                   (str "unknown: " arg)))
          (let [description (get-in description [:arguments i] "unknown")
                resolved (if (ifn? description) (description arg) description)]
            (println (str " [" resolved "]"))))
        (:arguments message)))))
  nil)

(def max-menu-request-count
  "The largest number of menu items we will request at a single time.
  I don't know how large a value is safe, and it may vary across
  different models."
  64)

(defn read-menu-responses
  "After a menu setup query (which is also used for things like track
  metadata) has returned a successful response including the number of
  items available, this requests all of the items, gathering the
  corresponding messages. The version with extra arguments is called
  internally to gather menus larger than can be requested in one
  transaction."
  ([player menu-field item-count]
   (read-menu-responses player menu-field item-count 0 []))
  ([player menu-field item-count offset received]
   (let [id (swap! (:counter player) inc)
         count (min (- item-count offset) max-menu-request-count)
         zero-field (number-field 0 4)
         offset-field (number-field offset 4)
         count-field (number-field count 4)
         ;; TODO: Based on LinkInfo-tracklist.txt looks like the last count-field should be item-count instead!
         request (build-message id 0x3000 menu-field offset-field count-field zero-field count-field zero-field)]
     (print "Sending > ")
     (describe-message request)
     (send-message player request)
     (let [result (loop [i 1
                         batch received]
                    (if-let [response (read-message player)]
                      (do (print "Received" i " > ")
                          (describe-message response)
                          (let [message-type (get-message-type response)]
                            (if (= message-type 0x4201)  ; Footer means we are done
                              batch
                              (recur (inc i) (if (= message-type 0x4101) (conj batch response) batch)))))
                      (do (timbre/error "Unable to read menu footer, returning partial result")
                          batch)))]
       (if (>= (+ offset count) item-count)
         result  ; We have now read all the items, can return the full result
         (recur player menu-field item-count (+ offset count) result))))))

(defn request-metadata
  "Sends the sequence of messages that request the metadata for a
  track in a media slot on the player."
  [player slot track]
  (let [id (swap! (:counter player) inc)
        menu-field (number-field [(:number player) 1 slot 1])
        setup (build-message id 0x2002 menu-field (number-field track 4))]
    (print "Sending > ")
    (describe-message setup)
    (send-message player setup)
    (when-let [response (read-message player)]
      (print "Received > ")
      (describe-message response)
      (when (= 0x4000 (get-message-type response))
        (let [item-count (get-in response [:arguments 1 :number])]
          (cond
            (= item-count 0xffffffff)
            (timbre/error "No track with id" track "in slot" slot "on player" (:target player))

            (pos? item-count)
            (let [metadata (read-menu-responses player menu-field item-count)]
              ;; TODO build and return more compact structure.
              )

            :else
            (timbre/error "No metadata available from player" (:target player) "slot" slot "track" track
                          "(are you using a valid, unused player number?)")))))))

(defn request-track-list
  "Sends the sequence of messages that request the track list for a
  media slot on the player. If `order` is given it is sent as the
  second numeric argument in the request; a value of 2 seems to sort
  the tracks by artist names. There may be other options as yet
  undiscovered."
  ([player slot]
   (request-track-list player slot 0))
  ([player slot order]
   (let [id (swap! (:counter player) inc)
         menu-field (number-field [(:number player) 1 slot 1])
         setup (build-message id 0x1004 menu-field (number-field order 4))]
     (print "Sending > ")
     (describe-message setup)
     (send-message player setup)
     (when-let [response (read-message player)]
       (print "Received > ")
       (describe-message response)
       (when (= 0x4000 (get-message-type response))
         (let [item-count (get-in response [:arguments 1 :number])]
           (cond
             (= item-count 0xffffffff)
             (timbre/error "No track listing available for slot" slot "on player" (:target player))

             (pos? item-count)
             (let [tracks (read-menu-responses player menu-field item-count)]
               ;; TODO build and return more compact structure?
               tracks)

             :else
             (timbre/error "No track listing available for slot" slot "on player"  (:target player)))))))))

(defn request-playlist
  "Sends the sequence of messages that request a playlist. If `folder?`
  is true, it means you are asking for a folder within the playlist
  menu, not for an actual playlist. The root playlist menu is obtained
  by requesting folder 0."
  ([player slot id]
   (request-playlist player slot id false 0))
  ([player slot id folder?]
   (request-playlist player slot id folder? 0))
  ([player slot id folder? sort]
   (let [tx (swap! (:counter player) inc)
         menu-field (number-field [(:number player) 1 slot 1])
         setup (build-message tx 0x1105 menu-field (number-field sort 4) (number-field id 4)
                              (number-field (if folder? 1 0) 4))]
     (print "Sending > ")
     (describe-message setup)
     (send-message player setup)
     (when-let [response (read-message player)]
       (print "Received > ")
       (describe-message response)
       (when (= 0x4000 (get-message-type response))
         (let [item-count (get-in response [:arguments 1 :number])]
           (cond
             (= item-count 0xffffffff)
             (timbre/error "No playlist with id" id (str (when folder? "as folder ") "available for slot") slot
                           "on player" (:target player))

             (pos? item-count)
             (let [entries (read-menu-responses player menu-field item-count)]
               ;; TODO build and return more compact structure?
               entries)

             :else
             (timbre/error "No playlist with id" id (str (when folder? "as folder ") "available for slot") slot
                           "on player" (:target player)))))))))

(defn request-album-art
  "Sends the sequence of messages that request album art (using the
  artwork id as reported in a metadata or track list response) for a
  track in a media slot on the player. Displays the image retrieved
  and returns the response containing it."
  [player slot artwork-id]
  (let [id (swap! (:counter player) inc)
        menu-field (number-field [(:number player) 1 slot 1])
        setup (build-message id 0x2003 menu-field (number-field artwork-id 4))]
    (print "Sending > ")
    (describe-message setup)
    (send-message player setup)
    (when-let [response (read-message player)]
      (print "Received > ")
      (describe-message response)
      (if (= 0x4002 (get-message-type response))
        (let [img (javax.imageio.ImageIO/read
                   (java.io.ByteArrayInputStream. (byte-array (get-in response [:arguments 3 :data]))))
              frame (javax.swing.JFrame. (str "Album art " artwork-id))]
          (doto frame
            (.add (javax.swing.JLabel. (javax.swing.ImageIcon. img)))
            (.setSize 180 120)
            (.setVisible true))
          response)
        (timbre/error "No artwork with id" id "available for slot" slot "on player" (:target player))))))

(defn request-beat-grid
  "Sends the sequence of messages that request the beat grid for a
  track in a media slot on the player. Returns the response containing
  it."
  [player slot track]
  (let [id (swap! (:counter player) inc)
        menu-field (number-field [(:number player) 8 slot 1])
        setup (build-message id 0x2204 menu-field (number-field track 4))]
    (print "Sending > ")
    (describe-message setup)
    (send-message player setup)
    (when-let [response (read-message player)]
      (print "Received > ")
      (describe-message response)
      (if (= 0x4602 (get-message-type response))
        (let [data (get-in response [:arguments 3 :data])]
          (loop [i 20
                 result [{:beat 0 :time 0}]]
            (if (< i (count data))
              (recur (+ i 16)
                     (conj result {:beat (get data i) :time (bytes->number (reverse (subvec data (+ i 4) (+ i 8))))}))
              result)))
        (timbre/error "No beat grid for track" id "available for slot" slot "on player" (:target player))))))

(defn- draw-waveform-preview-300
  "Draws the waveform represented by the specified byte vector, using
  the algorithm described by Austin Wright."
  [waveform track]
  (let [img      (java.awt.image.BufferedImage. (count waveform) 32 java.awt.image.BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics img)
        frame    (javax.swing.JFrame. (str "Waveform for track " track))]
    (doseq [x (range (count waveform))]
      (let [segment   (get waveform x)
            intensity (bit-and segment 2r11100000)
            height    (bit-and segment 2r00011111)
            color     (java.awt.Color. intensity intensity 255)]
        (.setColor graphics color)
        (.drawLine graphics x 31 x (- 31 height))))
    (doto frame
      (.add (javax.swing.JLabel. (javax.swing.ImageIcon. img)))
      (.pack)
      (.setVisible true))))

(defn- draw-waveform-preview-900
  "Draws the waveform preview that newer firmware versions seem to
  return."
  [waveform track]
  (let [img      (java.awt.image.BufferedImage. 400 32 java.awt.image.BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics img)
        frame    (javax.swing.JFrame. (str "Waveform for track " track))]
    (doseq [x (range 400)]
      (let [segment   (* x 2)
            height    (get waveform segment)
            intensity (bit-or 2r1110000 (bit-shift-left (bit-and (get waveform (inc segment)) 2r111) 5))
            color     (java.awt.Color. intensity intensity 255)]
        (.setColor graphics color)
        (.drawLine graphics x 31 x (- 31 height))))
    (doto frame
      (.add (javax.swing.JLabel. (javax.swing.ImageIcon. img)))
      (.pack)
      (.setVisible true))))

(defn request-waveform-preview
  "Sends the sequence of messages that request the waveform preview
  for a track in a media slot on the player. Displays the image
  retrieved and returns the response containing it."
  [player slot track]
  (let [id (swap! (:counter player) inc)
        menu-field (number-field [(:number player) 8 slot 1])
        setup (build-message id 0x2004 menu-field (number-field 1 4) (number-field track 4) (number-field 0 4)
                             (blob-field []))]
    (print "Sending > ")
    (describe-message setup)
    (send-message player setup)
    (when-let [response (read-message player)]
      (print "Received > ")
      (describe-message response)
      (if (= 0x4402 (get-message-type response))
        (do
          (let [waveform (:data (last (:arguments response)))]
            (case (count waveform)
              300 (draw-waveform-preview-300 waveform track)
              900 (draw-waveform-preview-900 waveform track)
              (timbre/error "Don't know how to draw a waveform preview of length" (count waveform))))
          response)
        (timbre/error "No waveform preview for track" track "available for slot" slot "on player" (:target player))))))

(defn experiment
  "Sends a sequence of messages like those requesting metadata, but
  using a different message kind, and with a variable list of argument
  fields. For example, to retrieve the root menu, invoke as:
  (experiment player slot 0x1000 (number-field 0 4)
              (number-field 0x00ff 4))"
  [player slot kind & args]
  (let [id (swap! (:counter player) inc)
        menu-field (number-field [(:number player) 1 slot 1])
        setup (apply build-message (concat [id kind menu-field] args))]
    (print "Sending > ")
    (describe-message setup)
    (send-message player setup)
    (when-let [response (read-message player)]
      (print "Received > ")
      (describe-message response)
      (when (= 0x4000 (get-message-type response))
        (let [item-count (get-in response [:arguments 1 :number])]
          (if (pos? item-count)
            (let [results (read-menu-responses player menu-field item-count)]
              ;; TODO build and return more compact structure.
              )
            (timbre/error "No results available for this experiment.")))))))

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
                    :target        target-player-number
                    :counter       (atom 0)}
          greeting (number-field 1 4)]  ; The greeting packet is a 4-byte number field representing the number 1.
      (.setSoTimeout sock read-timeout)
      (send-bytes os (:bytes greeting))
      (if (not= (read-field is) greeting)
        (do (disconnect player)
            (timbre/error "Did not receive expected greeting response from player, closed."))
        (do
          (send-message player (build-setup-message pose-as-player-number))
          (let [response (read-message player)]
            (describe-message response)
            (if (= (get-message-type response) 0x4000)
              (do  ; Successful response
                (when (not= target-player-number (get-in response [:arguments 1 :number]))
                  (timbre/warn "Expected to receive target player number in response argument 1"))
                player)
              (do (disconnect player)
                  (timbre/error "Did not receive message type 0x4000 in response to setup message, closed.")))))))))

