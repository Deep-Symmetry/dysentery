(ns dysentery.edn-diagram
  "Implements an EDN-based domain-specific language for building SVG
  byte field diagrams."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [analemma.svg :as svg]
            [analemma.xml :as xml])
  (:import [java.util NoSuchElementException]))

;; Default style definitions.

(def serif-family
  "The font family we use for serif text."
  "Palatino, Georgia, Times New Roman, serif")

(def hex-family
  "The font family we use for hex text."
  "Courier New, monospace")



;; The global symbol table used when evaluating diagram source.

(def ^:dynamic *globals*
  "Holds the globals during the building of a diagram."
  nil)



;; The diagram-drawing functions we make available for conveniently
;; creating byte field diagrams.

(defn append-svg
  "Adds another svg element to the body being built up."
  [element]
  (swap! *globals* update 'svg-body concat [element]))

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
  (let [y    (+ ('diagram-y @*globals*) (* 0.5 height))
        body (for [i (range ('boxes-per-row @*globals*))]
               (let [x (+ ('left-margin @*globals*) (* (+ i 0.5) ('box-width @*globals*)))]
                 (svg/text (merge (dissoc options :labels :height)
                                  {:x                 x
                                   :y                 y
                                   :font-family       font-family
                                   :font-size         font-size
                                   :dominant-baseline "middle"
                                   :text-anchor       "middle"})
                           (nth labels i))))]
    (swap! *globals* (fn [current]
                       (-> current
                           (update 'diagram-y + height)
                       (update 'svg-body concat body))))))

(defn draw-row-header
  "Generates the label in the left margin which identifies the starting
  byte of a row. Defaults to a `:font-size` of 11 and `:font-family` of
  \"Courier New\" but these can be overridden as well. Other SVG text
  options can be supplied as keyword arguments, and they will be
  passed along."
  [label & {:keys [font-size font-family]
            :or   {font-size   11
                   font-family "Courier New, monospace"}
            :as   options}]
  (let [x (- ('left-margin @*globals*) 5)
        y (+ ('diagram-y @*globals*) (* 0.5 ('row-height @*globals*)))]
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
      :or   {height ('row-height @*globals*)}}]
  (swap! *globals* (fn [current]
                     (-> current
                         (update 'diagram-y + height)
                         (assoc 'box-index 0)))))

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
             height  ('row-height @*globals*)}}]
  (let [left   (+ ('left-margin @*globals*) (* ('box-index @*globals*) ('box-width @*globals*)))
        width  (* span ('box-width @*globals*))
        right  (+ left width)
        top    ('diagram-y @*globals*)
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
    (swap! *globals* update 'box-index + span)))

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
  ([hex {:keys [font-size font-family]
         :or   {font-size   18
                font-family hex-family}
         :as   options}]
   (svg/text (merge (dissoc options :label :subscript)
                    {:font-size         font-size
                     :font-family       font-family
                     :dominant-baseline "middle"
                     :text-anchor       "middle"})
             hex)))

(defn draw-group-label-header
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
  (let [y      ('diagram-y @*globals*)
        top    (+ y edge)
        left   ('left-margin @*globals*)
        right  (+ left (* ('box-width @*globals*) ('boxes-per-row @*globals*)))
        bottom (+ y (- height edge))]
    (draw-line left y left top)
    (draw-line right y right top)
    (append-svg (svg/line left top right (- bottom gap) :stroke "#000000" :stroke-width 1
                          :stroke-dasharray "1,1"))
    (draw-line right y right (- bottom gap))
    (append-svg (svg/line left (+ top gap) right bottom :stroke "#000000" :stroke-width 1
                          :stroke-dasharray "1,1"))
    (draw-line left (+ top gap) left bottom)
    (draw-line left bottom left (+ y height))
    (draw-line right bottom right (+ y height)))
  (swap! *globals* update 'diagram-y + height))

(defn draw-bottom
  "Ends the diagram by drawing a line across the box area. Needed if the
  preceding action was drawing a gap, to avoid having to draw an empty
  row of boxes, which would extend the height of the diagram without
  adding useful information."
  []
  (let [y    ('diagram-y @*globals*)
        left ('left-margin @*globals*)]
    (draw-line left y (+ left (* ('box-width @*globals*) ('boxes-per-row @*globals*))) y)))



;; The parser/evaluator for our domain-specific language, a subset of
;; Clojure which requires no compilation at runtime, and which cannot
;; do dangerous things like Java interop, arbitrary I/O, or infinite
;; iteration.

(declare limited-eval) ; Need to forward declare our evaluator because of mutual recursion.

(defn- and-form
  "Implements the `and` special form."
  [args scope line]
  (loop [remainder args
         result    true]
    (if (empty? remainder)
      result
      (when-let [current (limited-eval (first remainder) scope line)]
        (recur (rest remainder) current)))))

(defn- or-form
  "Implements the `or` special form."
  [args scope line]
  (loop [remainder args]
    (when (seq remainder)
      (if-let [current (limited-eval (first remainder) scope line)]
        current
        (recur (rest remainder))))))

(def special-forms
  "All the symbols which get special handing in our domain-specific
  language. Keys are what they get represented by in the parsed form,
  values are the functions that implement their meanings when
  evaluating the form."
  {::and and-form
   ::or  or-form})

(defmacro self-bind-symbols
  "Builds a map in which each of the supplied list of symbols is mapped
  to itself."
  [syms]
  `(zipmap '[~@syms] ~syms))

(def core-bindings
  "The Clojure core library functions we want to make available for building diagrams."
  (self-bind-symbols [*
                      +
                      -
                      /
                      <
                      <=
                      =
                      ==
                      >
                      >=
                      apply
                      assoc
                      assoc-in
                      bigdec
                      bigint
                      biginteger
                      bit-and
                      bit-and-not
                      bit-clear
                      bit-flip
                      bit-not
                      bit-or
                      bit-set
                      bit-shift-left
                      bit-shift-right
                      bit-test
                      bit-xor
                      boolean
                      bounded-count
                      butlast
                      byte
                      char
                      comp
                      compare
                      concat
                      conj
                      cons
                      constantly
                      contains?
                      count
                      dec
                      dedupe
                      denominator
                      disj
                      dissoc
                      distinct
                      distinct?
                      double
                      drop
                      drop-last
                      drop-while
                      empty
                      empty?
                      even?
                      every-pred
                      every?
                      false?
                      ffirst
                      filter
                      filterv
                      find
                      first
                      flatten
                      float
                      fnext
                      fnil
                      format
                      frequencies
                      get
                      get-in
                      group-by
                      hash-map
                      hash-set
                      identical?
                      identity
                      inc
                      inst-ms
                      int
                      interleave
                      interpose
                      into
                      juxt
                      keep
                      keep-indexed
                      key
                      keys
                      keyword
                      last
                      list
                      list*
                      long
                      map
                      map-indexed
                      mapcat
                      mapv
                      max
                      max-key
                      merge
                      merge-with
                      min
                      min-key
                      mod
                      name
                      next
                      nfirst
                      nil?
                      nnext
                      not
                      not-any?
                      not-empty
                      not-every?
                      not=
                      nth
                      nthnext
                      nthrest
                      num
                      numerator
                      odd?
                      partial
                      partition
                      partition-all
                      partition-by
                      peek
                      pop
                      pos?
                      quot
                      rand
                      rand-int
                      rand-nth
                      random-sample
                      rationalize
                      reduce
                      reduce-kv
                      reduced
                      reductions
                      rem
                      remove
                      replace
                      rest
                      reverse
                      rseq
                      rsubseq
                      run!
                      second
                      select-keys
                      seq
                      sequence
                      set
                      set/difference
                      set/index
                      set/intersection
                      set/join
                      set/map-invert
                      set/project
                      set/rename
                      set/rename-keys
                      set/select
                      set/subset?
                      set/superset?
                      set/union
                      short
                      shuffle
                      some
                      some-fn
                      some?
                      sort
                      sort-by
                      sorted-map
                      sorted-map-by
                      sorted-set
                      sorted-set-by
                      split-at
                      split-with
                      str
                      str/blank?
                      str/capitalize
                      str/ends-with?
                      str/includes?
                      str/join
                      str/lower-case
                      str/replace
                      str/replace-first
                      str/reverse
                      str/split
                      str/split-lines
                      str/starts-with?
                      str/trim
                      str/trim-newline
                      str/triml
                      str/trimr
                      str/upper-case
                      subs
                      subseq
                      subvec
                      symbol
                      take
                      take-last
                      take-nth
                      take-while
                      unreduced
                      unsigned-bit-shift-right
                      update
                      update-in
                      val
                      vals
                      vec
                      vector
                      zero?
                      zipmap]))

(def xml-bindings
  "The Analemma XML-manipulation functions we make available for
  building diagrams."
  (self-bind-symbols [xml/add-attrs
                      xml/add-content
                      xml/emit
                      xml/emit-attrs
                      xml/emit-tag
                      xml/filter-xml
                      xml/get-attrs
                      xml/get-content
                      xml/get-name
                      xml/get-xml-map
                      xml/has-attrs?
                      xml/has-content?
                      xml/merge-attrs
                      xml/parse-xml
                      xml/parse-xml-map
                      xml/set-attrs
                      xml/set-content
                      xml/transform-xml
                      xml/update-attrs]))

(def svg-bindings
  "The Analemma SVG-creation functions we make available for building
  diagrams."
  (self-bind-symbols [svg/add-style
                      svg/animate
                      svg/animate-color
                      svg/animate-motion
                      svg/animate-transform
                      svg/circle
                      svg/defs
                      svg/draw
                      svg/ellipse
                      svg/group
                      svg/image
                      svg/line
                      svg/parse-inline-css
                      svg/path
                      svg/polygon
                      svg/rect
                      svg/rgb
                      svg/rotate
                      svg/style
                      svg/style-map
                      svg/svg
                      svg/text
                      svg/text-path
                      svg/transform
                      svg/translate
                      svg/translate-value
                      svg/tref
                      svg/tspan]))

(def diagram-bindings
  "Our own functions which we want to make available for building
  diagrams."
  (self-bind-symbols [draw-bottom
                      draw-box
                      draw-column-headers
                      draw-gap
                      draw-group-label-header
                      draw-line
                      draw-row-header
                      hex-text
                      label-text
                      next-row]))

(def initial-globals
  "The contents of the global symbol table that will be established at
  the start of reading a diagram definition."
  (merge
   {'left-margin   40 ; Space for row offsets and other leading marginalia.
    'right-margin  1  ; Space at the right, currently just enough to avoid clipping the rightmost box edges.
    'bottom-margin 1  ; Space at bottom, currently just enough to avoid clipping bottom box edges.
    'box-width     40 ; How much room each byte (or bit) box takes up.

    'boxes-per-row 16 ; How many individual byte/bit boxes fit on each row.
    'row-height    30 ; The height of a standard row of boxes.

    ;; Web-safe font families used for different kinds of text.
    'serif-family serif-family
    'hex-family   hex-family

    ;; Some nice default background colors, used in `remotedb` message headers.
    'green  "#a0ffa0"
    'yellow "#ffffa0"
    'pink   "#ffb0a0"
    'cyan   "#a0fafa"
    'purple "#e4b5f7"

    ;; Special forms we handle when evaluating diagram code.
    'and ::and
    'or  ::or

    ;; Values used to track the current state of the diagram being created:
    'box-index 0 ; Row offset of the next box to be drawn.
    'diagram-y 5 ; The y coordinate of the top of the next row to be drawn.
    'svg-body  '()} ; Diagram gets built up here, SVG wrapper added at end once height known.

   ;; The groups of functions that we make available for use by diagram code.
   core-bindings
   svg-bindings
   xml-bindings
   diagram-bindings))

(defn emit-svg
  "Outputs the finished SVG."
  []
  (xml/emit (apply svg/svg ('svg-body @*globals*))))

(defn resolve-symbol
  "Locate a symbol in the scope chain or the global symbol table."
  [sym scope line]
  (if (str/starts-with? (name sym) "'")
    ;; This is a quoted symbol, so just return a symbol without the leading quote mark.
    (symbol (subs (name sym) 1))
    ;; Try looking the symbol up in the scope chain.
    (if-let [result (get-in scope [:bindings sym])]
      result
      (if-let [next-scope (:next scope)]
        (recur sym next-scope line)  ; Haven't reached the end of the scope chain yet.
        ;; Not found in scope chain, look it up in the global symbol table.
        (let [not-found (Object.)
              result (get @*globals* sym not-found)]
          (if (= result not-found)
            (throw (NoSuchElementException. (str "Unbound symbol " sym " in expression starting at line " line)))
            result))))))

(defn limited-eval
  "A bare-bones expression evaluator focused on safely executing
  instructions to build a diagram. `expr` is the parsed expression,
  `scope` is the chain of lexical scopes, and `line` is the line
  number at which the expression started."
  [expr scope line]
  (cond
    (symbol? expr)  ; Symbols get looked up in the scope chain and global symbol table.
    (resolve-symbol expr scope line)

    (list? expr)  ; Lists are function calls. Evaluate the function, and if it looks good, its arguments and call.
    (let [f (limited-eval (first expr) scope line)]
      (cond
        (contains? special-forms f)  ; Special forms get evaluated in their own unique ways.
        (let [handler (special-forms f)]
          (handler (rest expr) scope line))

        (ifn? f) ; Ordinary functions get applied to the evaluated remainder of the list.
        (apply f (map #(limited-eval % scope line) (rest expr)))

        :else
        (throw (IllegalArgumentException. (str (first expr) " (" f
                                               ") is not a function in expression starting at line " line)))))

    (vector? expr)  ; Vectors just need their elements recursively evaluated.
    (mapv #(limited-eval % scope line) expr)

    (set? expr)  ; Sets, too, just need their elements recurively evaluated.
    (set (map #(limited-eval % scope line) expr))

    (map? expr)  ; Maps get their keys and values recursively evaluated.
    (reduce-kv (fn [m k v]
                 (assoc m (limited-eval k scope line) (limited-eval v scope line)))
               {}
               expr)

    :else ; We don't have anything special to do with this, just return it.
    expr))

(defn build-diagram
  "Reads an EDN-based diagram specification and returns the
  corresponding SVG. `diagram` can either be a string (URL or file
  name), a `java.io.File`, or a `java.io.Reader` from which the
  diagram specification can be read."
  [diagram]
  (binding [*globals* (atom initial-globals)]
    (with-open [reader (io/reader diagram)]
      (let [reader    (clojure.lang.LineNumberingPushbackReader. reader)
            eof       (Object.)
            opts      {:eof eof}
            read-expr (fn []
                        (try
                          (edn/read opts reader)
                          (catch Exception e
                            (throw (RuntimeException. (str "Problem reading diagram at line "
                                                           (.getLineNumber reader)) e)))))]
        (loop [line (.getLineNumber reader)
               expr (read-expr)]
          (when (not= eof expr)
            (if (= expr (symbol "'")) ; A quote at the top level just means we discard the next expression.
              (let [quoted (edn/read opts reader)]
                (when (= eof quoted)
                  (throw (RuntimeException. (str "EOF reading quoted expression starting at line " line)))))
              (limited-eval expr {} line)) ; Evaluate the line normally.
            (recur (.getLineNumber reader) (read-expr))))
        (spit "/tmp/test.svg" (emit-svg))))))
