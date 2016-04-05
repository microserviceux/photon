(ns photon.handler
  (:require [ring.middleware.reload :as reload]
            [clojure.tools.logging :as log]
            [photon.streams :as streams]
            [schema.core :as s]
            [compojure.route :as route]
            [compojure.core :as cc]
            [ring.util.http-response :as http]
            [ring.util.response :as response]
            [ring.middleware.json :as rjson]
            [cheshire.core :as json]
            [cheshire.generate :refer [add-encoder]]
            [clojure.pprint :as pp]
            [compojure.api.sweet :refer :all]
            [ring.swagger.swagger2 :as rs]
            [ring.swagger.json-schema-dirty :refer :all]
            [clojure.core.async :refer [go-loop go timeout <! >! close!]]
            [chord.http-kit :refer [wrap-websocket-handler]]
            [ring.middleware.params :as pms]
            [ring.middleware.multipart-params :as mp]
            [ring.middleware.session :refer [wrap-session]]
            [immutant.web.async :as async]
            [immutant.web.middleware :as iwm]
            [photon.api :as api]
            [photon.security :as sec]
            [compojure.handler :refer [site]])
  (:import (java.io ByteArrayInputStream)))

(defn ws-send! [ch msg]
  (async/send! ch (pr-str msg)))

(defn f-ws-projections-handler-hk [stream]
  (fn [{:keys [ws-channel] :as req}]
    (go
      (loop [t 0]
        (if-let [{:keys [message]} (<! ws-channel)]
          (do
            (<! (timeout t))
            (>! ws-channel
                (if (contains? message :projection-name)
                  (api/projection stream (:projection-name message))
                  (api/projections-without-val stream)))
            (recur 1000))
          (do
            (close! ws-channel)
            (prn "closed.")))))))

(defn f-ws-streams-handler-hk [stream]
  (fn [{:keys [ws-channel] :as req}]
    (go
      (loop [t 0]
        (if-let [{:keys [message]} (<! ws-channel)]
          (do
            (<! (timeout t))
            (let [all (:current-value
                       (api/projection stream "__streams__"))
                  filtered (zipmap (keys all)
                                   (map #(dissoc % :schemas) (vals all)))]
              (>! ws-channel filtered))
            (recur 1000))
          (do
            (close! ws-channel)
            (prn "closed.")))))))

(defn f-ws-stats-handler-hk [stream]
  (fn [{:keys [ws-channel] :as req}]
    (go
      (loop [t 0]
        (if-let [{:keys [message]} (<! ws-channel)]
          (do
            (<! (timeout t))
            (let [stats-stream @(:stats stream)
                  stats-rt (api/runtime-stats stream)
                  all-stats {:stats (merge stats-stream stats-rt)}]
              (>! ws-channel all-stats))
            (recur 1000))
          (do
            (close! ws-channel)
            (prn "closed.")))))))

(defn ws-route-projections-hk [stm]
  (cc/defroutes m-ws-route-projections
    (let [ws-projections-handler (f-ws-projections-handler-hk stm)]
      (GET "/ws-projections" []
           (wrap-websocket-handler ws-projections-handler)))))

(defn ws-route-streams-hk [stm]
  (cc/defroutes m-ws-route-streams
    (let [ws-streams-handler (f-ws-streams-handler-hk stm)]
      (GET "/ws-streams" []
           (wrap-websocket-handler ws-streams-handler)))))

(defn ws-route-stats-hk [stm]
  (cc/defroutes m-ws-route-stats
    (let [ws-stats-handler (f-ws-stats-handler-hk stm)]
      (GET "/ws-stats" []
           (wrap-websocket-handler ws-stats-handler)))))

(defmulti on-open (fn [ch stream]
                    (let [req (async/originating-request ch)]
                      (apply str (rest (:path-info req))))))

(defmethod on-open "ws-stats" [ch stream]
  (let [req (async/originating-request ch)]
    (go
      (loop [t 0]
        (<! (timeout t))
        (when (async/open? ch)
          (let [stats-stream @(:stats stream)
                stats-rt (api/runtime-stats stream)
                all-stats {:stats (merge stats-stream stats-rt)}]
            (ws-send! ch all-stats)
            (recur 1000)))))))

(defmethod on-open "ws-projections" [ch stream]
  (let [req (async/originating-request ch)
        projection-name (:projection-name (:params req))]
    (go
      (loop [t 0]
        (<! (timeout t))
        (when (async/open? ch)
          (ws-send! ch
                    (if (nil? projection-name)
                      (api/projections-without-val stream)
                      (api/projection stream projection-name)))
          (recur 1000))))))

(defmethod on-open "ws-streams" [ch stream]
  (let [req (async/originating-request ch)]
    (go
      (loop [t 0]
        (<! (timeout t))
        (when (async/open? ch)
          (ws-send! ch
                    (:current-value (api/projection stream "__streams__")))
          (recur 1000))))))

(defn f-ws-handler [stream]
  {:on-open (fn [ch] (on-open ch stream))
   :on-close (fn [ch {:keys [code reason]}])
   :on-error (fn [ch t])
   :on-message (fn [ch msg])})

(defn ws-route [stm]
  (iwm/wrap-websocket
   (cc/defroutes m-ws-route
     (let [ws-handler (f-ws-handler stm)]
       (GET "/ws-stats" [] {:connected "ok"})
       (GET "/ws-streams" [] {:connected "ok"})
       (GET "/ws-projections" [] {:connected "ok"})))
   (f-ws-handler stm)))

(defn app [http-kit? ms m-sec]
  (defapi app-no-reload
    {:swagger {:ui "/api-docs"
               :spec "/swagger.json"
               :data {:info {:title "Photon API"
                             :description "Photon API"}
                      :tags [{:name "api", :description "Core API"}]}}}
    (context "/auth" []
             :tags ["auth"]
             :middleware [(sec/basic-or-session-mw m-sec)
                          (sec/cors-mw m-sec)
                          (sec/authenticated-mw m-sec)]
             ;; TODO: Add refresh-token functionality
             (GET "/login" {session :session}
                  (assoc (http/ok {:logged-in true})
                         :session (assoc session :identity "user")))
             (GET "/logout" {session :session}
                  (-> (http/ok {:logged-out true})
                      (assoc :session (dissoc session :identity))))
             (GET "/token" req
                  (http/ok (sec/auth-credentials-response m-sec req))))
    (context "/export" []
             :no-doc true
             :middleware [(sec/qs->token-mw m-sec) (sec/session-or-token-mw m-sec)
                          (sec/cors-mw m-sec) (sec/authenticated-mw m-sec)]
             (GET "/stream/:stream-name" [stream-name]
                  :path-params [stream-name :- s/Str]
                  (let [f (api/stream->file ms stream-name)]
                    (-> (response/file-response (.getAbsolutePath f))
                        (http/header "Content-Type" "application/octet-stream")
                        (http/header "Content-Disposition" (str "attachment; filename=" stream-name ".pev"))
                        (http/header "Content-Length" (.length f))))))
    (context "/api" []
             :tags ["api"]
             :middleware [(sec/qs->token-mw m-sec)
                          (sec/session-or-token-mw m-sec)
                          (sec/cors-mw m-sec)
                          (sec/authenticated-mw m-sec)]
             (GET "/ping" []
                  (http/ok {:auth "ok"}))
             (GET "/streams" []
                  :return api/StreamInfoMap
                  :summary "Obtain a list of active streams
                     and their current size"
                  (http/ok (api/streams ms)))
             (GET "/projection-keys" []
                  :return api/ProjectionKeyMap
                  :summary "Obtain a list of the names (IDs)
          of the current active projections"
                  (http/ok (api/projection-keys ms)))
             (GET "/projections" []
                  :return api/ProjectionList
                  :summary "Obtain a list of the states of the current active
          projections without their computed reduction values"
                  (http/ok (api/projections ms)))
             (GET "/projection/:projection-name" [projection-name]
                  :path-params [projection-name :- s/Str]
                  :return api/ProjectionResponse
                  :summary "Obtain the current status of a given projection,
          including the latest computed reduction value"
                  (let [pres (api/projection ms projection-name)]
                    (if (nil? pres)
                      (http/not-found)
                      (http/ok pres))))
             (DELETE "/projection/:projection-name" [projection-name]
                     :path-params [projection-name :- s/Str]
                     :return api/PostResponse
                     :summary "Stop and delete a running projection"
                     (http/ok
                      (api/delete-projection! ms projection-name)))
             (GET "/stream-contents/:stream-name" [stream-name]
                  :path-params [stream-name :- s/Str]
                  :return api/StreamContentsResponse
                  :summary "Obtain a list (maximum of 50) of events contained
          in a given stream"
                  (http/ok (api/stream ms stream-name :limit 50)))
             (GET "/event/:stream-name/:order-id" [stream-name order-id]
                  :path-params [stream-name :- s/Str order-id :- s/Str]
                  :return api/EventResponse
                  :summary "Obtain the event identified by a given stream name
          and an order ID"
                  (let [res (api/event ms stream-name (read-string order-id))]
                    (if (nil? res) (http/not-found) (http/ok res))))
             (POST "/projection" [& request]
                   :body [body api/ProjectionTemplate]
                   :return api/PostResponse
                   :summary "Add a projection"
                   (http/ok (api/post-projection! ms request)))
             (POST "/event" [& request]
                   :body [body api/EventTemplate]
                   :return api/PostResponse
                   :summary "Add an event"
                   (let [res (api/post-event! ms request)]
                     (http/ok res)))
             (POST "/event/:stream-name" [& request]
                   :no-doc true
                   (api/post-event! ms request))
             (mp/wrap-multipart-params
              (cc/POST "/new-stream" {params :params}
                       (http/ok {:status "OK"
                                 :stream-name (api/new-stream ms params)}))))
    (context "/ws" []
             :middleware [(sec/qs->token-mw m-sec) (sec/session-or-token-mw m-sec)
                          (sec/cors-mw m-sec) (sec/authenticated-mw m-sec)]
             (routes (rjson/wrap-json-body
                      (pms/wrap-params
                       (site (if http-kit?
                               (routes (ws-route-projections-hk ms)
                                       (ws-route-streams-hk ms)
                                       (ws-route-stats-hk ms))
                               (routes (ws-route ms)))))
                      {:keywords? true})))
    (GET "/" []
         :no-doc true
         (response/resource-response "index.html" {:root "public/ui"}))
    (route/resources "/")
    (route/not-found (http/not-found "Not found")))
  (wrap-session (reload/wrap-reload #'app-no-reload)))
