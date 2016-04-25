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
