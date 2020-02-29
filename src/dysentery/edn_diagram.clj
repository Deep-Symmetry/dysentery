(ns dysentery.edn-diagram
  "Implements an EDN-based domain-specific language for building SVG
  byte field diagrams."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [analemma.svg :as svg]
            [analemma.xml :as xml])
  (:import [java.util NoSuchElementException]))

(def serif-family
  "The font family we use for serif text."
  "Palatino, Georgia, Times New Roman, serif")

(def hex-family
  "The font family we use for hex text."
  "Courier New, monospace")

(def ^:dynamic *globals*
  "Holds the globals during the building of a diagram."
  nil)

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

(def initial-globals
  "The contents of the global symbol table that will be established at
  the start of reading a diagram definition."
  {'left-margin   40           ; Space for row offsets and other leading marginalia.
   'right-margin  1            ; Space at the right, currently just enough to avoid clipping the rightmost box edges.
   'bottom-margin 1            ; Space at bottom, currently just enough to avoid clipping bottom box edges.
   'box-width     40           ; How much room each byte (or bit) box takes up.
   'boxes-per-row 16           ; How many individual byte/bit boxes fit on each row.
   'row-height    30           ; The height of a standard row of boxes.
   'serif-family  serif-family ; Font family used for serif text.
   'hex-family    hex-family   ; Font family used for hex text.

   ;; Special forms we handle
   'and ::and
   'or  ::or

   ;; Functions which can be called to build diagrams.
   'draw-column-headers draw-column-headers

   ;; Clojure core library functions we make available for building diagrams.
   '+                        +
   '-                        -
   '*                        *
   '/                        /
   '=                        =
   '==                       ==
   '<                        <
   '<=                       <=
   '>                        >
   '>=                       >=
   'apply                    apply
   'assoc                    assoc
   'assoc-in                 assoc-in
   'bigdec                   bigdec
   'bigint                   bigint
   'biginteger               biginteger
   'bit-and                  bit-and
   'bit-and-not              bit-and-not
   'bit-clear                bit-clear
   'bit-flip                 bit-flip
   'bit-not                  bit-not
   'bit-or                   bit-or
   'bit-set                  bit-set
   'bit-shift-left           bit-shift-left
   'bit-shift-right          bit-shift-right
   'bit-test                 bit-test
   'bit-xor                  bit-xor
   'blank?                   str/blank?
   'boolean                  boolean
   'bounded-count            bounded-count
   'butlast                  butlast
   'byte                     byte
   'capitalize               str/capitalize
   'char                     char
   'comp                     comp
   'compare                  compare
   'concat                   concat
   'conj                     conj
   'cons                     cons
   'constantly               constantly
   'contains?                contains?
   'count                    count
   'dec                      dec
   'dedupe                   dedupe
   'denominator              denominator
   'disj                     disj
   'dissoc                   dissoc
   'distinct                 distinct
   'distinct?                distinct?
   'double                   double
   'drop                     drop
   'drop-last                drop-last
   'drop-while               drop-while
   'empty                    empty
   'empty?                   empty?
   'ends-with?               str/ends-with?
   'even?                    even?
   'every-pred               every-pred
   'every?                   every?
   'false?                   false?
   'ffirst                   ffirst
   'filter                   filter
   'filterv                  filterv
   'find                     find
   'first                    first
   'flatten                  flatten
   'float                    float
   'fnext                    fnext
   'fnil                     fnil
   'format                   format
   'frequencies              frequencies
   'get                      get
   'get-in                   get-in
   'group-by                 group-by
   'hash-map                 hash-map
   'hash-set                 hash-set
   'identical?               identical?
   'identity                 identity
   'inc                      inc
   'includes?                str/includes?
   'inst-ms                  inst-ms
   'int                      int
   'interleave               interleave
   'interpose                interpose
   'into                     into
   'join                     str/join
   'juxt                     juxt
   'keep                     keep
   'keep-indexed             keep-indexed
   'key                      key
   'keys                     keys
   'keyword                  keyword
   'last                     last
   'list                     list
   'list*                    list*
   'long                     long
   'lower-case               str/lower-case
   'map                      map
   'map-indexed              map-indexed
   'map-invert               set/map-invert
   'mapcat                   mapcat
   'mapv                     mapv
   'max                      max
   'max-key                  max-key
   'merge                    merge
   'merge-with               merge-with
   'min                      min
   'min-key                  min-key
   'mod                      mod
   'name                     name
   'next                     next
   'nfirst                   nfirst
   'nil?                     nil?
   'nnext                    nnext
   'not                      not
   'not-any?                 not-any?
   'not-empty                not-empty
   'not-every?               not-every?
   'not=                     not=
   'nth                      nth
   'nthnext                  nthnext
   'nthrest                  nthrest
   'num                      num
   'numerator                numerator
   'odd?                     odd?
   'partial                  partial
   'partition                partition
   'partition-all            partition-all
   'partition-by             partition-by
   'peek                     peek
   'pop                      pop
   'pos?                     pos?
   'quot                     quot
   'rand                     rand
   'rand-int                 rand-int
   'rand-nth                 rand-nth
   'random-sample            random-sample
   'rationalize              rationalize
   'reduce                   reduce
   'reduce-kv                reduce-kv
   'reduced                  reduced
   'reductions               reductions
   'rem                      rem
   'remove                   remove
   'replace                  replace
   'rest                     rest
   'reverse                  reverse
   'rseq                     rseq
   'rsubseq                  rsubseq
   'run!                     run!
   'second                   second
   'select-keys              select-keys
   'seq                      seq
   'sequence                 sequence
   'set                      set
   'set-difference           set/difference
   'set-index                set/index
   'set-intersection         set/intersection
   'set-join                 set/join
   'set-project              set/project
   'set-rename               set/rename
   'set-rename-keys          set/rename-keys
   'set-select               set/select
   'set-union                set/union
   'short                    short
   'shuffle                  shuffle
   'some                     some
   'some-fn                  some-fn
   'some?                    some?
   'sort                     sort
   'sort-by                  sort-by
   'sorted-map               sorted-map
   'sorted-map-by            sorted-map-by
   'sorted-set               sorted-set
   'sorted-set-by            sorted-set-by
   'split-at                 split-at
   'split-lines              str/split-lines
   'split-with               split-with
   'starts-with?             str/starts-with?
   'str                      str
   'string-replace           str/replace
   'string-replace-first     str/replace-first
   'string-reverse           str/reverse
   'string-split             str/split
   'subs                     subs
   'subseq                   subseq
   'subset?                  set/subset?
   'superset?                set/superset?
   'subvec                   subvec
   'symbol                   symbol
   'take                     take
   'take-last                take-last
   'take-nth                 take-nth
   'take-while               take-while
   'trim                     str/trim
   'triml                    str/triml
   'trimr                    str/trimr
   'trim-newline             str/trim-newline
   'unreduced                unreduced
   'unsigned-bit-shift-right unsigned-bit-shift-right
   'update                   update
   'update-in                update-in
   'upper-case               str/upper-case
   'val                      val
   'vals                     vals
   'vec                      vec
   'vector                   vector
   'zero?                    zero?
   'zipmap                   zipmap

   ;; Values used to track the current state of the diagram being created:
   'box-index 0 ; Row offset of the next box to be drawn.
   'diagram-y 5 ; The y coordinate of the top of the next row to be drawn.
   'svg-body  '()})        ; Diagram gets built up here, SVG wrapper added at end once height known.

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

(declare limited-eval)

(defn special-form?
  "Checks whether a function is actually one of our special forms."
  [f]
  (and (keyword? f)
       (= (namespace f) (namespace ::and))))

(defn handle-special-form
  "Checks whether a function call is actually one of our special forms,
  and if so, implements those special semantics."
  [form args scope line]
  (case form
    ::and
    (loop [remainder args
           result    true]
      (if (empty? remainder)
        result
        (when-let [current (limited-eval (first remainder) scope line)]
          (recur (rest remainder) current))))

    ::or
    (loop [remainder args]
      (when (seq remainder)
        (if-let [current (limited-eval (first remainder) scope line)]
          current
          (recur (rest remainder)))))))

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
        (special-form? f)
        (handle-special-form f (rest expr) scope line)

        (ifn? f)
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
  corresponding SVG. `diagram` can either be a string or a
  `java.io.Reader` from which the diagram specification can be read."
  [diagram]
  (binding [*globals* (atom initial-globals)]
    (let [reader    (if (string? diagram)
                      (java.io.StringReader. diagram)
                      diagram)
          reader    (clojure.lang.LineNumberingPushbackReader. reader)
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
            (println expr (limited-eval expr {} line)))
          (recur (.getLineNumber reader) (read-expr))))
      (spit "/tmp/test.svg" (emit-svg)))))
