; Copyright (C) 2013, 2014, 2015 Dr. Thomas Schank  (DrTom@schank.ch, Thomas.Schank@algocon.ch)
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.dispatcher.retry
  (:require
    [cider-ci.dispatcher.job :as job]
    [cider-ci.dispatcher.stateful-entity :as stateful-entity]
    [cider-ci.dispatcher.task :as task]
    [cider-ci.utils.config :as config :refer [get-config]]
    [cider-ci.utils.messaging :as messaging]
    [cider-ci.utils.rdbms :as rdbms :refer [get-ds]]

    [clojure.java.jdbc :as jdbc]

    [clj-logging-config.log4j :as logging-config]
    [clojure.tools.logging :as logging]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug]
    ))


;#### retry and resume ########################################################

(defn retry-unpassed-tasks [job params]
  (->> (jdbc/query (rdbms/get-ds)
                   ["SELECT id, job_id FROM tasks
                    WHERE job_id = ?
                    AND state IN ('failed','aborted','aborting')" (:id job)])
       (map #(task/create-trial % params))
       doall))

(defn retry-and-resume [job-id params]
  (catcher/with-logging {}
    (let [job (job/get-job job-id)
          job-id (:id job) ]
      (when-not job
        (throw (ex-info "Job not found" {:status 422})))
      (jdbc/update! (get-ds) :jobs
                    (merge (select-keys params [:resumed_by, :resumed_at])
                           {:state "pending"})
                    ["id = ?" job-id])
      (retry-unpassed-tasks job {:created_by (:resumed_by params)})
      (job/evaluate-and-update job-id))))

;#### retry-task ##############################################################

(defn retry-task [task-id params]
  (let [task (task/get-task task-id)
        task-id (:id task) ]
    (when-not task
      (throw (ex-info "Task not found" {:status 404})))
    (task/create-trial task params)))



;#### debug ###################################################################
;(debug/debug-ns *ns*)
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
