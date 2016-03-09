(ns photon.muon
  (:require [photon.streams :as streams]
            [muon-clojure.common :as mcc]
            [muon-clojure.server :as mcs]
            [com.stuartsierra.component :as component]
            [photon.api :as api]
            [clojure.core.async :refer [go <! chan tap]]
            [clojure.tools.logging :as log])
  (:import (org.reactivestreams Publisher)
           (java.util Map)))

(defrecord PhotonMicroservice [stream-manager]
  mcs/MicroserviceStream
  (stream-mappings [this]
    ;; TODO: Explore the case of pure hot/cold streams
    [{:endpoint "test" :type :cold
      :fn-process (fn [params]
                    (log/info "Test:" params)
                    (clojure.core.async/to-chan [{:val 1} {:val 2}]))}
     {:endpoint "stream" :type :hot-cold
      :fn-process (fn [params]
                    (log/info "PhotonMS:" params)
                    (streams/stream->ch stream-manager params))}])
  mcs/MicroserviceRequest
  (request-mappings [this]
    [{:endpoint "projection"
      :fn-process (fn [resource]
                    (log/info ":::: QUERY " (pr-str resource))
                    (api/projection stream-manager
                                    (:projection-name resource)))}
     {:endpoint "projection-keys"
      :fn-process (fn [resource]
                    (api/projection-keys stream-manager))}
     {:endpoint "events"
      :fn-process (fn [ev]
                    (log/trace ":::: EVENTS" (pr-str ev))
                    (api/post-event!
                     stream-manager
                     (clojure.walk/keywordize-keys ev)))}
     {:endpoint "projections"
      :fn-process (fn [resource]
                    (let [params (clojure.walk/keywordize-keys resource)]
                      (api/post-projection! stream-manager params)))}]))

(defrecord MuonService [options stream-manager]
  component/Lifecycle
  (start [component]
    (if (nil? (:muon component))
      (try
        (let [stream-manager (:manager stream-manager)
              impl (PhotonMicroservice. stream-manager)
              projections (:proj-ch stream-manager)
              conf {:rabbit-url (:amqp.url options)
                    :service-identifier (:microservice.name options)
                    :tags ["photon" "eventstore"]
                    :implementation impl}
              comp (mcs/micro-service conf)
              ms (component/start comp)]
          (go
            (loop [new-proj (<! projections)]
              (when-not (nil? new-proj)
                (log/info "Registering new projection"
                          (:projection-name new-proj) "into muon...")
                (mcc/stream-source (:muon ms)
                                   (str "projection/" (:projection-name new-proj))
                                   :hot
                                   (fn [resource]
                                     (let [ch (chan)]
                                       (tap (:mult new-proj) ch)
                                       ch)))
                (recur (<! projections)))))
          (assoc component :muon ms))
        (catch Exception e
          (log/info "Muon could not be started:" (.getMessage e))
          (log/info "Falling back to muon-less mode!")
          component))
      component))
  (stop [component]
    (if (nil? (:muon component))
      component
      (do
        (component/stop (:muon component))
        (assoc component :muon nil)))))

(defn muon-service [options]
  (map->MuonService {:options options}))
