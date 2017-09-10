(ns commands.exec
  (:require [re-db.d :as d]
            [goog.events :as events]
            [commands.registry :as registry]
            [clojure.set :as set]
            [maria.util :as util]))

(def context (volatile! {}))
(def last-selections (volatile! (list)))

(defn set-context! [values]
  (vswap! context merge values))

(def -before-exec (volatile! {}))
(defn before-exec [key cb]
  (vswap! -before-exec assoc key cb))

(defn get-context
  ([] (get-context nil))
  ([event-attrs]
   (let [{:keys [block-view] :as context} @context]
     (-> context
         (assoc :signed-in? (d/get :auth-public :signed-in?))
         (merge event-attrs)
         (cond-> block-view
                 (merge {:editor (.getEditor block-view)}
                        (select-keys block-view [:block :blocks])))))))

(defn apply-context
  "Add contextual data to command"
  [context {:keys [exec-pred intercept-pred] :as command-entry}]
  (let [exec? (boolean (or (nil? exec-pred) (exec-pred context)))
        intercept? (if intercept-pred (intercept-pred context)
                                      exec?)]
    (assoc command-entry
      :exec? exec?
      :intercept? intercept?)))

(defn get-command
  "Returns command associated with name, if it exists.
  Add contextual data, :exec? and :intercept?."
  [context name]
  (apply-context context (get @registry/commands name)))

(defn exec-command
  "Execute a command (returned by `get-command`)"
  [context {:keys [command exec? intercept? name]}]
  (let [result (when exec? (command context))]
    (when exec? (util/log-ret "Executed command:" name))
    (and result (not= result (.-Pass js/CodeMirror)))))

(defn exec-command-name
  ([name] (exec-command-name name (get-context)))
  ([name context]
   (exec-command context (get-command context name))))

(defn keyset-commands
  ([] (keyset-commands #{} (get-context)))
  ([modifiers-down current-context]
   (->> @registry/mappings
        (keep (fn [[keyset {:keys [exec]}]]
                (when (set/subset? modifiers-down keyset)
                  ;; change this later for multi-step keysets
                  (some->> (seq exec)
                           (map (partial get-command current-context))
                           (filter :exec?)))))
        (apply concat)
        (distinct))))

(defn contextual-commands
  [context]
  (->> (vals @registry/commands)
       (sequence (comp (map #(apply-context context %))
                       (filter :exec?)))))

(def reverse-compare (fn [a b] (compare b a)))

(defn init-listeners []
  (let [clear-keys #(d/transact! [[:db/add :commands :modifiers-down #{}]
                                  [:db/add :commands :which-key/active? false]])
        clear-timeout! #(some-> (d/get :commands :which-key/timeout) (js/clearTimeout))
        clear-which-key! #(do (clear-timeout!)
                              (d/transact! [[:db/add :commands :which-key/active? false]]))

        which-key-delay 1500
        handle-keydown (fn [e]
                         (let [keycode (registry/normalize-keycode (.-keyCode e))
                               {keys-down         :modifiers-down
                                which-key-active? :which-key/active?} (d/entity :commands)
                               modifier? (contains? registry/modifiers keycode)
                               command-names (seq (registry/get-keyset-commands (conj keys-down keycode)))
                               context (when command-names
                                         (get-context {:keycode keycode
                                                       :key     (.-key e)}))
                               the-commands (when command-names
                                              (->> (map #(get-command context %) command-names)
                                                   (filter #(or (:exec? %) (:intercept? %)))
                                                   (sort-by :priority reverse-compare)))
                               _ (when the-commands
                                   (doseq [f (vals @-before-exec)]
                                     (f)))
                               ;; NOTE
                               ;; only running 1 command, sorting by priority.
                               results (when the-commands
                                         (take 1 (filter identity (map #(exec-command context %) the-commands))))]
                           (when (and which-key-active? (= keycode 27))
                             (d/transact! [[:db/add :commands :which-key/active? false]]))
                           ;; if we use Tab as a modifier,
                           ;; we'll stop its default behaviour
                           #_(when (= 9 keycode)
                               (.preventDefault e)
                               (.stopPropagation e))

                           (when (seq (filter identity results))
                             (util/stop! e)
                             (clear-which-key!))

                           (clear-timeout!)

                           (when modifier?
                             (d/transact! [[:db/update-attr :commands :modifiers-down conj keycode]
                                           [:db/add :commands :which-key/timeout
                                            (js/setTimeout
                                              #(let [keys-down (d/get :commands :modifiers-down)]
                                                 (when (and (seq keys-down)
                                                            (not= keys-down #{(registry/endkey->keycode "shift")}))
                                                   (d/transact! [[:db/add :commands :which-key/active? true]])))
                                              which-key-delay)]]))))]

    (clear-keys)

    ;; NOTE: 'keydown' listeners MUST be activated in the 'capture' phase.
    ;;       Otherwise, CodeMirror/editor state can change before the command is activated.

    (events/listen js/window "keydown" handle-keydown true)

    (events/listen js/window "keyup"
                   (fn [e]
                     (let [keycode (registry/normalize-keycode (.-keyCode e))]
                       (when (registry/modifiers keycode)
                         (d/transact! [[:db/update-attr :commands :modifiers-down disj keycode]])
                         (when (empty? (d/get :commands :modifiers-down))
                           (d/transact! [[:db/add :commands :which-key/active? false]]))))) true)

    (events/listen js/window "mousedown"
                   (fn [e]
                     (doseq [command (-> (conj (d/get :commands :modifiers-down) (.-button e))
                                         (registry/get-keyset-commands))]
                       (exec-command-name command))))

    (events/listen js/window #js ["blur" "focus"] #(when (= (.-target %) (.-currentTarget %))
                                                     (clear-keys)))))

(defonce _ (init-listeners))