(ns dysentery.diagram
  (:require [clojure.string :as str]
            [analemma.svg :as svg]
            [analemma.xml :as xml]))

(def left-margin
  "The space for row offsets and other marginalia."
  40)

(def right-margin
  "The space at the right, currently just enough to avoid clipping the
  rightmost box edges."
  1)

(def bottom-margin
  "The space at the bottom, currently just enough to avoid clipping the
  bottom box edges."
  1)

(def box-width
  "How much room each byte (or bit) box takes up."
  40)

(def boxes-per-row
  "How many individual byte/bit boxes fit on each row."
  16)

(def row-height
  "The height of a standard row of boxes."
  30)

(def serif-family
  "The font family we use for serif text."
  "Palatino, Georgia, Times New Roman, serif")

(def hex-family
  "The font family we use for hex text."
  "Courier New, monospace")

(def state
  "Holds the SVG being built up, the y coordinate of the top of the row
  being drawn, and the index of the current box being drawn."
  (atom {:index 0 ; The current box being drawn.
         :y     5 ; The y coordinate of the top of the current line. Intial value forms a top margin.
         :body  '()})) ; Rows get added to this, svg wrapper is added at end once size is known.

(defn append-svg
  "Adds another svg element to the body being built up."
  [element]
  (swap! state update :body concat [element]))

(defn draw-column-headers
  "Generates the header row that identifies each byte/bit box. By
  default uses the lower-case hex digits in increasing order, but you
  can pass in your own list of `:labels`. Normally consumes 14
  vertical pixels, but you can pass in a different `:height`. Defaults
  to a `:font-size` of 7 and `:font-family` of \"Courier New\" but
  these can be overridden as well. Other SVG text options can be
  supplied as keyword arguments, and they will be passed along."
  [& {:keys [labels height font-size font-family]
      :or   {labels      (str/split "0,1,2,3,4,5,6,7,8,9,a,b,c,d,e,f" #",")
             height      14
             font-size   11
             font-family "Courier New, monospace"}
      :as   options}]
  (let [y    (+ (:y @state) (* 0.5 height))
        body (for [i (range boxes-per-row)]
               (let [x (+ left-margin (* (+ i 0.5) box-width))]
                 (svg/text (merge (dissoc options :labels :height)
                                  {:x                 x
                                   :y                 y
                                   :font-family       font-family
                                   :font-size         font-size
                                   :dominant-baseline "middle"
                                   :text-anchor       "middle"})
                           (nth labels i))))]
    (swap! state (fn [current]
                   (-> current
                       (update :y + height)
                       (update :body concat body))))))

(defn draw-row-header
  "Generates the label in the left margin which identifies the starting
  byte of a row. Defaults to a `:font-size` of 7 and `:font-family` of
  \"Courier New\" but these can be overridden as well. Other SVG text
  options can be supplied as keyword arguments, and they will be
  passed along."
  [label & {:keys [font-size font-family]
            :or   {font-size   11
                   font-family "Courier New, monospace"}
            :as   options}]
  (let [x (- left-margin 5)
        y (+ (:y @state) (* 0.5 row-height))]
    (append-svg (svg/text (merge options
                                 {:x                 x
                                  :y                 y
                                  :font-family       font-family
                                  :font-size         font-size
                                  :dominant-baseline "middle"
                                  :text-anchor       "end"})
                          label))))

(defn draw-line
  "Adds a line to the SVG being built up. SVG line attributes can be
  overridden by passing additional keyword/value pairs."
  [x1 y1 x2 y2]
  (append-svg (svg/line x1 y1 x2 y2 :stroke "#000000" :stroke-width 1)))

(defn next-row
  "Advances drawing to the next row of boxes, reseting the index to 0.
  The height of the row defaults to `row-height` but can be overridden
  by passing a different value with `:height`."
  [& {:keys [height]
      :or   {height row-height}}]
  (swap! state (fn [current]
                 (-> current
                     (update :y + height)
                     (assoc :index 0)))))

(defn draw-box
  "Draws a single byte or bit box in the current row at the current
  index. Text content can be passed with `:text`. The default size is
  that of a single byte (or bit) but this can be overridden with
  `:span`. Normally draws all borders, but you can supply the set you
  want drawn in `:borders`. The background can be filled with a color
  passed with `:fill`. Box height defaults to `row-height`, but that
  can be changed with `:height` (you will need to supply the same
  height override when calling `next-row`)."
  [& {:keys [text span borders fill height]
      :or   {span    1
             borders #{:left :right :top :bottom}
             height  row-height}}]
  (let [left   (+ left-margin (* (:index @state) box-width))
        width  (* span box-width)
        right  (+ left width)
        top    (:y @state)
        bottom (+ top height)]
    (when fill (append-svg (svg/rect left top height width :fill fill)))
    (when (borders :top) (draw-line left top right top))
    (when (borders :bottom) (draw-line left bottom right bottom))
    (when (borders :right) (draw-line right top right bottom))
    (when (borders :left) (draw-line left top left bottom))
    (when text
      (append-svg (xml/add-attrs text
                             :x (/ (+ left right) 2.0)
                             :y (+ top 1 (/ height 2.0))
                             :dominant-baseline "middle"
                             :text-anchor "middle")))
    (swap! state update :index + span)))

(defn label-text
  "Builds an SVG text object to represent a named value, with an
  optional subscript. Defaults are established for the font size,
  family, and style, but they can be overridden in `options`,
  and other SVG attributes can be passed that way as well."
  ([label]
   (label-text label nil nil))
  ([label subscript]
   (label-text label subscript nil))
  ([label subscript {:keys [ font-size font-family font-style]
                     :or   {font-size   18
                            font-family serif-family
                            font-style  "italic"}
                     :as   options}]
   (apply svg/text (concat
                    [(merge (dissoc options :label :subscript)
                            {:font-size         font-size
                             :font-family       font-family
                             :font-style        font-style
                             :dominant-baseline "middle"
                             :text-anchor       "middle"})
                     label]
                    (when subscript
                      [(svg/tspan {:baseline-shift "sub"
                                   :font-size      "70%"}
                                  subscript)])))))

(defn hex-text
  "Builds an SVG text object to represent a hexadecimal value.
  Defaults are established for the font size and family, but they can
  be overridden in `options`, and other SVG attributes can be passed
  that way as well."
  ([hex]
   (hex-text hex nil))
  ([hex {:keys [ font-size font-family]
         :or   {font-size   18
                font-family hex-family}
         :as   options}]
   (svg/text (merge (dissoc options :label :subscript)
                          {:font-size         font-size
                           :font-family       font-family
                           :dominant-baseline "middle"
                           :text-anchor       "middle"})
             hex)))

(defn draw-label-header
  "Creates a small borderless box used to draw the textual label headers
  used below the byte labels for `remotedb` message diagrams.
  Arguments are the number of colums to span and the text of the
  label."
  [span text]
  (draw-box :span span :text (label-text text nil {:font-size 12}) :borders #{} :height 14))

(defn draw-gap
  "Draws an indication of discontinuity. Takes a full row, the default
  height is 50 and the default gap is 10, and the default edge on
  either side of the gap is 5, but all can be overridden with keyword
  arguments."
  [& {:keys [height gap edge]
      :or   {height 70
             gap    10
             edge   15}}]
  (let [y     (:y @state)
        top   (+ y edge)
        right (+ left-margin (* box-width boxes-per-row))
        bottom (+ y (- height edge))]
    (draw-line left-margin y left-margin top)
    (draw-line right y right top)
    (append-svg (svg/line left-margin top right (- bottom gap) :stroke "#000000" :stroke-width 1
                          :stroke-dasharray "1,1"))
    (draw-line right y right (- bottom gap))
    (append-svg (svg/line left-margin (+ top gap) right bottom :stroke "#000000" :stroke-width 1
                          :stroke-dasharray "1,1"))
    (draw-line left-margin (+ top gap) left-margin bottom)
    (draw-line left-margin bottom left-margin (+ y height))
    (draw-line right bottom right (+ y height)))
  (swap! state update :y + height))

(defn draw-bottom
  "Ends the diagram by drawing a line across the box area. Needed if the
  preceding action was drawing a gap, to avoid having to draw an empty
  row of boxes, which would extend the height of the diagram without
  adding useful information."
  []
  (let [y (:y @state)]
    (draw-line left-margin y (+ left-margin (* box-width boxes-per-row)) y)))

(defn emit-svg
  "Outputs the finished SVG."
  []
  (xml/emit (apply svg/svg (:body @state))))

(def green
  "The green color we use in `remotedb` message headers"
  "#a0ffa0")

(def yellow
  "The yellow color we use in `remotedb` message headers"
  "#ffffa0")

(def pink
  "The pink color we use in `remotedb` message headers"
  "#ffb0a0")

(def cyan
  "The cyan color we use in `remotedb` message headers"
  "#a0fafa")

(def purple
  "The purple color we use in `remotedb` message headers"
  "#e4b5f7")


;; Figure 48: Cue point response message.

(draw-column-headers)

(draw-label-header 5 "start")
(draw-label-header 5 "TxID")
(draw-label-header 3 "type")
(draw-label-header 2 "args")
(draw-label-header 1 "tags")
(next-row :height 18)

(draw-row-header "00")
(draw-box :text (hex-text "11") :fill green)
(draw-box :span 4 :text (hex-text "872349ae") :fill green)
(draw-box :text (hex-text "11") :fill yellow)
(draw-box :span 4 :text (label-text "TxID") :fill yellow)
(draw-box :text (hex-text "10") :fill pink)
(draw-box :span 2 :text (hex-text "4702") :fill pink)
(draw-box :text (hex-text "0f") :fill cyan)
(draw-box :text (hex-text "09") :fill cyan)
(draw-box :text (hex-text "14") :fill purple)
(next-row)

(draw-row-header "10")
(draw-box :span 4 :text (svg/text {:font-size         18
                                   :font-family       hex-family
                                   :dominant-baseline "middle"
                                   :text-anchor       "middle"}
                                  "0000000c "
                                  (svg/tspan {:font-family       serif-family
                                              :font-size         16
                                              :font-weight       "light"
                                              :dominant-baseline "middle"}
                                             "(12)"))
          :fill purple)
(draw-box :text (hex-text "06") :borders #{:left :top :bottom} :fill purple)
(doseq [val ["06" "06" "03" "06" "06" "06" "06" "03" "00" "00"]]
  (draw-box :text (hex-text val) :borders #{:top :bottom} :fill purple))
(draw-box :text (hex-text "00") :borders #{:right :top :bottom} :fill purple)
(next-row)

(draw-row-header "20")
(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (hex-text "00002104"))
(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (hex-text "00000000"))
(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (label-text "length" "1"))
(draw-box :text (hex-text "14"))
(next-row)

(draw-row-header "30")
(draw-box :span 4 :text (label-text "length" "1"))
(draw-box :span 12 :text (svg/text {:font-family serif-family}
                                   "Cue and loop point bytes") :borders #{:left :right :top})
(next-row)
(draw-gap)

(draw-box)
(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (hex-text "00000036"))
(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (label-text "num" "hot"))
(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (label-text "num" "cue"))
(next-row)

(draw-box :text (hex-text "11"))
(draw-box :span 4 :text (label-text "length" "2"))
(draw-box :text (hex-text "14"))
(draw-box :span 4 :text (label-text "length" "2"))
(draw-box :span 6 :text (svg/text {:font-family serif-family}
                                   "Unknown bytes") :borders #{:left :right :top})
(next-row)
(draw-gap)
(draw-bottom)


(spit "/tmp/test.svg" (emit-svg))
