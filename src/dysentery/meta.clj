(ns dysentery.meta
  "Works on getting metadata about a particular rekordbox track from a
  particular player."
  (require [dysentery.finder :as finder]
           [dysentery.util :as util]))


(defn send-bytes
  "Send a vector of byte values to an output stream."
  [os v]
  (println "Sending " (vec (map #(format "%02x" (util/unsign %)) v)))
  (let [obuf (byte-array (map util/make-byte v))]
    (.write os obuf 0 (count obuf))
    (.flush os)))

;; Temporary while working on analysis
(defonce last-bytes (atom nil))

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

(defn split-id-bytes
  "Split the four bytes of a track or message ID, in the order they
  need to be sent to the player."
  [id]
  [(bit-and (bit-shift-right id 24) 0xff)
   (bit-and (bit-shift-right id 16) 0xff)
   (bit-and (bit-shift-right id 8) 0xff)
   (bit-and id 0xff)])

(def message-separator
  "This 6 byte marker used in packets sent and received in metadata
  queries seems to be some sort of field separator."
  [0x11 0x87 0x23 0x49 0xae 0x11])

(def setup-packet
  "This packet seems to be needed to put the connection into a state
  where we can make queries."
  [0x10 0x00 0x00 0x0f 0x01 0x14 0x00 0x00 0x00 0x0c 0x06 0x00 0x00 0x00 0x00
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x11 0x00 0x00 0x00])

(defn build-packet
  "Constructs a packet to be sent as part of a metadata query."
  [message-id payload]
  (concat message-separator (split-id-bytes message-id) payload))

(defn request-track-metadata
  "Asks a CDJ for metadata about a particular track in a particular slot."
  [player slot rekordbox-id our-player-id]
  (when-let [device (finder/device-given-number player)]
    (let [slot (case slot
                 :usb 3
                 :sd 2)]
      (with-open [sock (java.net.Socket. (:address device) 1051)
                  os (.getOutputStream sock)
                  is (.getInputStream sock)]
        
        (.setSoTimeout sock 3000)

        (send-bytes os [0x11 00 00 00 01])
        (recv-bytes is)  ; Should get five bytes back

        (send-bytes os (build-packet 0xfffffffe (concat setup-packet [our-player-id])))
        (recv-bytes is)  ; Should get 42 bytes back

        (send-bytes os (build-packet 1 (concat [0x10 0x20 0x02 0x0f 0x02 0x14 0x00 0x00
                                                0x00 0x0c 0x06 0x06 0x00 0x00 0x00 0x00
                                                0x00 0x00 0x00 0x00 0x00 0x00 0x11 our-player-id
                                                0x01 slot 0x01 0x11]
                                               (split-id-bytes rekordbox-id))))
        (recv-bytes is)  ; Should get 42 bytes back
        
        (send-bytes os (build-packet 2 [0x10 0x30 0x00 0x0f 0x06 0x14 0x00 0x00
                                        0x00 0x0c 0x06 0x06 0x06 0x06 0x06 0x06
                                        0x00 0x00 0x00 0x00 0x00 0x00 0x11 our-player-id
                                        0x01 slot 0x01 0x11 0x00 0x00 0x00 0x00
                                        0x11 0x00 0x00 0x00 0x06 0x11 0x00 0x00
                                        0x00 0x00 0x11 0x00 0x00 0x00 0x06 0x11
                                        0x00 0x00 0x00 0x00]))
        (reset! last-bytes (recv-bytes is))
        (.close sock)))))

(defn build-int
  "Given a byte array, the index of the first byte of an integer
  value, and the number of bytes that make it up, calculates the
  integer that the bytes represent."
  [arr index size]
  (loop [i (inc index)
         left (dec size)
         result (util/unsign (aget arr index))]
    (if (pos? left)
      (recur (inc i) (dec left) (+ (* result 256) (util/unsign (aget arr i))))
      result)))

(defn analyze
  "Try to make sense of the track metadata in last-bytes."
  []
  (let [[packet-len arr] @last-bytes 
        title-len (build-int arr 90 4)
        artist-len (build-int arr (+ 184 (* 2 title-len)) 4)]
    (println "Title length:" title-len)
    (println "Title:" (String. arr 94 (* (dec title-len) 2) "UTF-16"))
    (println "Artist length:" artist-len)
    (println "Artist:" (String. arr (+ 188 (* 2 title-len)) (* (dec artist-len) 2) "UTF-16"))))
