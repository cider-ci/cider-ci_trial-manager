; Copyright (C) 2013, 2014, 2015 Dr. Thomas Schank  (DrTom@schank.ch, Thomas.Schank@algocon.ch)
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.dispatcher.web
  (:refer-clojure :exclude [sync])
  (:require
    [cider-ci.auth.core :as auth]
    [cider-ci.auth.core]
    [cider-ci.auth.http-basic :as http-basic]
    [cider-ci.dispatcher.abort :as abort]
    [cider-ci.dispatcher.retry :as retry]
    [cider-ci.dispatcher.sync :as sync]
    [cider-ci.dispatcher.trial :as trial]
    [cider-ci.utils.http :as http]
    [cider-ci.utils.http-server :as http-server]
    [cider-ci.utils.messaging :as messaging]
    [cider-ci.utils.rdbms :as rdbms]
    [cider-ci.utils.routing :as routing]
    [clj-logging-config.log4j :as logging-config]
    [clojure.data :as data]
    [clojure.data.json :as json]
    [clojure.tools.logging :as logging]
    [compojure.core :as cpj]
    [compojure.handler :as cpj.handler]
    [drtom.logbug.debug :as debug]
    [drtom.logbug.ring :refer [wrap-handler-with-logging]]
    [drtom.logbug.thrown :as thrown]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.json]
    ))


(defonce conf (atom nil))

;##### update trial ###########################################################

(defn update-trial [id params]
  (trial/update (assoc (clojure.walk/keywordize-keys params)
                       :id id))
  {:status 200})

;##### status dispatch ########################################################

(defn status-handler [request]
  (let [stati {:rdbms (rdbms/check-connection)
               :messaging (messaging/check-connection)
               }]
    (if (every? identity (vals stati))
      {:status 200
       :body (json/write-str stati)
       :headers {"content-type" "application/json;charset=utf-8"} }
      {:status 511
       :body (json/write-str stati)
       :headers {"content-type" "application/json;charset=utf-8"} })))


;#### sync ####################################################################

(defn sync [request]
  (let [executor (:authenticated-executor request)]
    (if-not (:id executor)
      {:status 403}
      (sync/sync executor (:body request)))))


;#### routing #################################################################

(defn abort-job [request]
  (try
    (-> request :params :id abort/abort-job)
    {:status 202}
    (catch clojure.lang.ExceptionInfo e
      (if-let [status (-> e ex-data :status)]
        {:status status
         :body (.getMessage e)}
        {:status 500
         :body (thrown/stringify e)}))
    (catch Throwable th
      {:status 500
       :body (thrown/stringify th)})))


;#### routing #################################################################

(defn retry-and-resume [request]
  (try
    (-> request :params :id retry/retry-and-resume)
    {:status 202}
    (catch clojure.lang.ExceptionInfo e
      (if-let [status (-> e ex-data :status)]
        {:status status
         :body (.getMessage e)}
        {:status 500
         :body (thrown/stringify e)}))
    (catch Throwable th
      {:status 500
       :body (thrown/stringify th)})))

(defn retry-task [request]
  (try
    (let [trial (-> request :params :id retry/retry-task)]
      {:status 200
       :body (json/write-str trial)
       :headers {"content-type" "application/json;charset=utf-8"} })
    (catch clojure.lang.ExceptionInfo e
      (if-let [status (-> e ex-data :status)]
        {:status status
         :body (.getMessage e)}
        {:status 500
         :body (thrown/stringify e)}))
    (catch Throwable th
      {:status 500
       :body (thrown/stringify th)})))


;#### routing #################################################################

(defn build-routes [context]
  (cpj/routes

    (cpj/GET "/status" request #'status-handler)

    (cpj/POST "/jobs/:id/abort" _ #'abort-job)

    (cpj/POST "/jobs/:id/retry-and-resume" _ #'retry-and-resume)

    (cpj/POST "/tasks/:id/retry" _ #'retry-task)

    (cpj/PATCH "/trials/:id"
               {{id :id} :params data :body}
               (update-trial id data))

    (cpj/GET "/" [] "OK")

    (cpj/POST "/sync" _ #'sync)

    ))



(defn build-main-handler [context]
  ( -> (cpj.handler/api (build-routes (:context (:web @conf))))
       (wrap-handler-with-logging 'cider-ci.dispatcher.web)
       routing/wrap-shutdown
       (wrap-handler-with-logging 'cider-ci.dispatcher.web)
       (ring.middleware.json/wrap-json-body {:keywords? true})
       (wrap-handler-with-logging 'cider-ci.dispatcher.web)
       (routing/wrap-prefix context)
       (wrap-handler-with-logging 'cider-ci.dispatcher.web)
       (auth/wrap-authenticate-and-authorize-service)
       (wrap-handler-with-logging 'cider-ci.dispatcher.web)
       (http-basic/wrap {:executor true :user false :service true})
       (wrap-handler-with-logging 'cider-ci.dispatcher.web)
       (routing/wrap-log-exception)))


;#### the server ##############################################################

(defn initialize [new-conf]
  (reset! conf new-conf)
  (cider-ci.auth.core/initialize new-conf)
  (let [http-conf (-> @conf :services :dispatcher :http)
        context (str (:context http-conf) (:sub_context http-conf))]
    (http-server/start http-conf (build-main-handler context))))



;#### debug ###################################################################
;(debug/debug-ns 'cider-ci.auth.http-basic)
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
