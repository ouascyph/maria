(ns maria.blocks.blocks
  (:refer-clojure :exclude [empty?])
  (:require [magic-tree.core :as tree]
            [re-view.core :as v]
            [clojure.string :as string]
            [maria-commands.exec :as exec]
            [re-view-prosemirror.core :as pm]
            [re-view-prosemirror.markdown :as markdown]
            [cells.cell :as cell]
            [maria.util :as util]
            [re-db.d :as d]
            [cells.eval-context :as eval-context]
            [maria.eval :as e]))

(def view-index (volatile! {}))

(defn view [this]
  (@view-index (:id this)))

(defn editor [this]
  (some-> (view this)
          (.getEditor)))

(defn focused-block []
  (get-in @exec/context [:block-view :block]))

(defn mount [this view]
  (vswap! view-index assoc (:id this) view))

(defn unmount [this]
  (vswap! view-index dissoc (:id this)))

(defprotocol IBlock

  (empty? [this])
  (emit [this])
  (kind [this])
  (state [this])

  (render [this props]))

(defprotocol IAppend
  (append? [this other-block] "Return true if other block can be joined to next block")
  (append [this other-block] "Append other-block to this block."))

(defn focus!
  ([block]
   (focus! block nil))
  ([block coords]
   (when-not (view block)
     (v/flush!))
   (some-> (view block)
           (.focus coords))))

(defn update-view
  [block]
  (some-> (view block)
          (v/force-update)))

(defprotocol ICursor
  (get-history-selections [this])
  (put-selections! [this selections])

  (cursor-edge [this])
  (cursor-coords [this])
  (at-end? [this])
  (at-start? [this])
  (selection-expand [this])
  (selection-contract [this]))

(defprotocol IEval
  (eval! [this] [this kind value])
  (eval-log [this])
  (eval-log! [this value]))

(defprotocol IParagraph
  (prepend-paragraph [this]))

(extend-type nil IBlock
  (empty? [this] true))

(defrecord CodeBlock [id node]
  IBlock
  (state [this] node))

(defrecord ProseBlock [id doc]
  IBlock
  (state [this] doc))

(extend-protocol IEquiv
  CodeBlock
  (-equiv [this other] (and (= (:id this) (:id other))
                            (= (state this) (state other))))
  ProseBlock
  (-equiv [this other] (and (= (:id this) (:id other))
                            (= (state this) (state other)))))

(defn from-node
  "Returns a block, given a magic-tree AST node."
  [{:keys [tag] :as node}]
  ;; GET ID FROM NODE
  ;; CAN WE GET IT FROM DEF, DEFN, etc. in a structured way?
  ;; IE IF FIRST SYMBOL STARTS WITH DEF, READ NEXT SYMBOL
  (case tag
    :comment (->ProseBlock (d/unique-id) (.parse markdown/parser (:value node)))
    (:newline :space :comma nil) nil
    (->CodeBlock (d/unique-id) node)))

(defn create
  "Returns a block, given a kind (:code or :prose) and optional value."
  ([kind]
   (create kind (case kind
                  :prose ""
                  :code [])))
  ([kind value]
   (from-node {:tag   (case kind :prose :comment
                                 :code :base)
               :value value})))

(def emit-list
  "Returns the concatenated source for a list of blocks."
  (fn [blocks]
    (reduce (fn [out block]
              (let [source (-> (emit block)
                               (string/trim-newline))]
                (if-not (clojure.core/empty? source)
                  (str out source "\n\n")
                  out))) "\n" blocks)))

(defn from-source
  "Returns a vector of blocks from a ClojureScript source string."
  [source]
  (->> (tree/ast (:ns @e/c-env) source)
       :value
       (reduce (fn [blocks node]
                 (if-let [block (from-node node)]
                   (if (some-> (peek blocks)
                               (append? block))
                     (update blocks (dec (count blocks)) append block)
                     (conj blocks block))
                   blocks)) [])))


(defn join-blocks [blocks]
  (let [focused-block (focused-block)
        focused-view (some-> focused-block (editor))
        focused-coords (when (and focused-view (= :prose (kind focused-block)))
                         (some-> focused-view (pm/cursor-coords)))]
    (loop [blocks blocks
           dropped-indexes []
           block-to-focus nil
           i 0]
      (cond (> i (- (count blocks) 2))
            (do (when (and block-to-focus focused-coords)
                  (js/setTimeout
                    #(focus! block-to-focus focused-coords) 0))
                (with-meta blocks {:dropped dropped-indexes}))
            (append? (nth blocks i) (nth blocks (inc i)))
            (let [block (nth blocks i)
                  next-block (nth blocks (inc i))
                  next-focused-block (when (or (= (:id block) (:id focused-block))
                                               (= (:id next-block) (:id focused-block)))
                                       block)]
              (recur (util/vector-splice blocks i 2 [(append block next-block)])
                     (conj dropped-indexes (+ (inc i) (count dropped-indexes)))
                     (or next-focused-block block-to-focus)
                     i))
            :else (recur blocks dropped-indexes block-to-focus (inc i))))))

(defn ensure-blocks [blocks]
  (if-not (seq blocks)
    [(create :prose)]
    blocks))

(defn id-index [blocks id]
  (let [end-i (count blocks)]
    (loop [i 0] (cond
                  (= i end-i) nil
                  (= (:id (nth blocks i)) id) i
                  :else (recur (inc i))))))

(defn splice-block
  ([blocks block values]
   (splice-block blocks block 0 values))
  ([blocks block n values]
   (if (and (clojure.core/empty? values)
            (= 1 (count blocks)))
     (let [blocks (ensure-blocks nil)]
       (eval-context/dispose! block)
       (with-meta blocks {:before (first blocks)}))
     (let [index (cond-> (id-index blocks (:id block))
                         (neg? n) (+ n))
           n (inc (.abs js/Math n))
           result (util/vector-splice blocks index n values)
           start (dec index)
           end (-> index
                   (+ (count values)))]
       (assert index)
       (let [incoming-block-ids (into #{} (mapv :id values))
             replaced-blocks (subvec blocks index (+ index n))
             removed-blocks (set (filterv (comp (complement incoming-block-ids) :id) replaced-blocks))
             the-focused-block (focused-block)]
         (doseq [block removed-blocks]
           (eval-context/dispose! block))
         (when (removed-blocks the-focused-block)
           (let [focused-n (id-index blocks (:id the-focused-block))]
             (when-let [prev-block (->> (subvec blocks 0 focused-n)
                                        (reverse)
                                        (filter (complement removed-blocks))
                                        (first))]
               (focus! prev-block :end)))))
       (with-meta result
                  {:before (when-not (neg? start) (nth result start))
                   :after  (when-not (> end (dec (count result)))
                             (nth result end))})))))

(defn before [blocks block]
  (last (take-while (comp (partial not= (:id block)) :id) blocks)))

(defn after [blocks block]
  (second (drop-while (comp (partial not= (:id block)) :id) blocks)))