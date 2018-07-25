(ns dysentery.util
  "Utility functions that are likely to be widely useful"
  {:author "James Elliott"}
  (:require [clojure.math.numeric-tower :as math]))

(defn make-byte
  "Convert small integer to its signed byte equivalent. Necessary for
  convenient handling of values in the range 0-255, since Java does
  not have unsigned numbers."
  [val]
   (if (>= val 128)
     (byte (- val 256))
     (byte val)))

(defn unsign
  "Convert a signed byte to its unsigned int equivalent, in the range 0-255."
  [val]
  (bit-and val 0xff))

(defn build-int
  "Given a packet, the index of the first byte of an integer value,
  and the number of bytes that make it up, calculates the integer that
  the bytes represent."
  [packet index size]
  (loop [i (inc index)
         left (dec size)
         result (get packet index)]
    (if (pos? left)
      (recur (inc i) (dec left) (+ (* result 256) (get packet i)))
      result)))

(defn decompose-int
  "Given a number, split it into byte values suitable for inclusion
  in a packet."
  [n size]
  (loop [left   (dec size)
         result [(bit-and n 0xff)]
         n (bit-shift-right n 8)]
    (if (pos? left)
      (recur (dec left)
             (concat [(bit-and n 0xff)] result)
             (bit-shift-right n 8))
      (vec result))))
