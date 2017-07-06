(ns maria.frames.views
  (:require [re-view.core :as v :refer [defview]]
            [re-db.d :as d]
            [maria.frames.communication :as frame]
            [maria.persistence.local :as local]
            [cljs.core.match :refer-macros [match]]
            [clojure.set :as set]))



(defview frame-view
  {:spec/props              {:id         {:spec :String
                                          :doc  "unique ID for frame"}
                             :on-message {:spec :Function
                                          :doc  "Function to be called with messages from iFrame."}
                             #_:on-change  #_{:spec :Function
                                              :doc  "Function to be called with source, whenever it changes."}
                             #_:on-save    #_{:spec :Function
                                              :doc  "Function to be called with source, whenever 'save' command is fired"}}
   :send                    (fn [{:keys [id]} message]
                              (frame/send id message))
   :send-transactions       (fn [{:keys [db/transactions id]}]
                              (frame/send id [:db/transactions transactions]))
   :life/did-mount          (fn [{:keys [id on-message] :as this}]
                              (when on-message
                                (frame/listen id on-message))
                              (.sendTransactions this))
   :life/will-receive-props (fn [{:keys [on-message id db/transactions] {prev-id :id
                                                                         prev-tx :db/transactions} :view/prev-props :as this}]
                              (when-not (= id prev-id)
                                (frame/unlisten id on-message)
                                (frame/listen id on-message))
                              (when-not (= transactions prev-tx)
                                (.sendTransactions this)))
   :life/will-unmount       (fn [{:keys [id on-message]}]
                              (frame/unlisten id on-message))}
  [{:keys [id]}]
  [:iframe.maria-editor-frame
   {:src (str frame/child-origin "/eval.html#frame_" id)}])

(defview editor-frame-view
  {:spec/props              {:default-value :String}
   :life/will-mount         (fn [{:keys [source-id]}]
                              (local/init-storage source-id))
   :life/will-receive-props (fn [{source-id :source-id {prev-source-id :source-id} :view/prev-props}]
                              (when-not (= source-id prev-source-id)
                                (local/init-storage source-id)))}
  [{:keys [source-id on-save loading-message]}]
  (frame-view {:id              source-id
               :db/transactions [(d/entity source-id)
                                 [:db/add :layout 1 source-id]
                                 (if loading-message
                                   [:db/add source-id :loading-message loading-message]
                                   [:db/retract-attr source-id :loading-message])]
               :on-message      (fn [message]
                                  (match message
                                         [:source/update-local source-id value] (d/transact! [[:db/add source-id :local-value value]])
                                         [:source/persist source-id value] (on-save value)))}))
