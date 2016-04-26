(ns dysentery.core
  "This is the main class for running Dysentery as a self-contained
  JAR application."
  (:require [dysentery.finder :as finder]
            [dysentery.util :as util])
  (:gen-class))

(defn describe-devices
  "Print a descrption of the DJ Link devices found, and how to
  interact with them."
  [devices]
  (println "Found:")
  (doseq [device devices]
    (println "  " (:name device) (.toString (:address device))))
  (println)
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (print "To communicate create a virtual CDJ with address" (.toString (.getAddress address)))
    (println " and MAC address" (clojure.string/join ":" (map (partial format "%02x")
                                                             (map util/unsign (.getHardwareAddress interface)))))
    (println "and use broadcast address" (.toString (.getBroadcast address)))))

(defn find-devices
  "Run a loop that waits a few seconds to see if any DJ Link devices
  can be found on the network. If so, describe them and how to reach
  them."
  []
  (println "Looking for DJ Link devices...")
  (finder/start-if-needed)
  (Thread/sleep 2000)
  (loop [devices (finder/current-dj-link-devices)
         tries 3]
    (if (seq devices)
      (describe-devices devices)
      (if (zero? tries)
        (println "No DJ Link devices found; giving up.")
        (do
          (Thread/sleep 1000)
          (recur (finder/current-dj-link-devices) (dec tries))))))
  (finder/shut-down))

(defn -main
  "The entry point when inovked as a jar from the command line. Will
  eventually parse command line options, but for now test the device
  and interface identification code."
  [& args]
  (find-devices)
  (System/exit 0))
