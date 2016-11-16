(ns maria.tree.unwrap
  (:refer-clojure :exclude [*ns*])
  (:require [cljs.tools.reader.edn :as edn]
            [cljs.tools.reader :as r]
            [maria.tree.fn :refer [fn-walk]])
  (:require-macros [maria.tree.backtick :refer [template]]))

(def ^:dynamic *ns* (symbol "maria.user"))

(declare string)

(def *fixes
  {:list         [\( \)]
   :fn           ["#(" \)]
   :map          [\{ \}]
   :meta         ["^"]
   :reader-meta  ["#^"]
   :raw-meta     ["^"]
   :reader-macro ["#"]
   :regex        ["#\"" \"]
   :set          ["#{" \}]
   :string       [\" \"]
   :uneval       ["#_"]
   :var          ["#'"]
   :vector       [\[ \]]})

(defn wrap-children [left right children]
  (str left (apply str (map string children)) right))

#_(defn children? [{:keys [tag]}]
    (#{:list :fn :map :meta :set :vector :uneval} tag))

(defn string [{:keys [tag value options] :as node}]
  (when node
    (let [[lbracket rbracket] (get *fixes tag [])]
      (case tag
        :base (apply str (map string value))
        (:token :space :newline :comma) value
        (:vector
          :list
          :fn
          :map
          :set
          :var
          :uneval
          :reader-macro) (wrap-children lbracket rbracket value)
        :meta (str (:prefix options) (wrap-children lbracket rbracket value))
        (:string
          :regex) (str lbracket value rbracket)
        :keyword (str (cond->> value
                               (:namespaced? options) (str ":")))
        :comment (str ";" value)
        nil ""))))

(declare sexp)

(def printable-only? #{:comment :uneval :space :newline :comma})
(defn as-code [forms]
  (map sexp (filter #(not (printable-only? (:tag %))) forms)))

(defn sexp [{:keys [tag value options] :as node}]
  (when node
    (case tag
      :base (first (as-code value))

      (:space
        :newline
        :comma) nil

      :string value

      :token (edn/read-string value)
      :vector (vec (as-code value))
      :list (list* (as-code value))
      :fn (fn-walk (as-code value))
      :map (into {} (as-code value))
      :set (template #{~@(as-code value)})
      :var (template #'~(first (as-code value)))
      :reader-macro (r/read-string (string node))
      :meta (let [[m data] (as-code value)]
              (with-meta data (if (map? m) m {m true})))
      :regex (re-pattern value)
      :keyword (if (:namespaced? options)
                 (keyword *ns* (name value))
                 value)

      (:comment
        :uneval) nil)))
