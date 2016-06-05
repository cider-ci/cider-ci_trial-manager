; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.dispatcher.job
  (:require
    [cider-ci.dispatcher.stateful-entity :as stateful-entity]
    [cider-ci.utils.config :as config :refer [get-config]]
    [cider-ci.utils.messaging :as messaging]
    [cider-ci.utils.rdbms :as rdbms :refer [get-ds]]
    [clj-logging-config.log4j :as logging-config]
    [clojure.java.jdbc :as jdbc]
    [clojure.tools.logging :as logging]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug]
    ))


;#### evaluate and update #####################################################

(defn- get-task-states [job]
  (map :state (jdbc/query
                (rdbms/get-ds)
                ["SELECT state FROM tasks
                 WHERE job_id = ?" (:id job)])))

(defn- update-state-and-fire-if-changed [job-id new-state]
  (when (stateful-entity/update-state
          :jobs job-id new-state {:assert-existence true})
    (messaging/publish "job.updated" {:id job-id :state new-state})))

(defn get-job [job-id]
  (->> (jdbc/query (rdbms/get-ds)
                   ["SELECT * FROM jobs WHERE id = ?" job-id])
       first))

(defn- evalute-new-state [job task-states]
  (case (:state job)
    "aborted" "aborted"
    "aborting" (cond (every? #{"passed"} task-states) "passed"
                     (some #{"aborting"} task-states) "aborting"
                     (every? #{"passed" "failed" "aborted"} task-states) "aborted"
                     :else "aborting")
    (cond (every? #{"passed"} task-states) "passed"
          (some #{"executing"} task-states) "executing"
          (some #{"pending"} task-states) "pending"
          (some #{"aborted"} task-states) "aborted"
          (some #{"defective"} task-states) "defective"
          (some #{"failed"} task-states) "failed"
          :else (:state job))))

(defn evaluate-and-update [job-id]
  (catcher/with-logging {}
    (locking (str "evaluate-and-update job_id: " job-id)
      (let [job (get-job job-id)
            task-states (get-task-states job)
            new-state (evalute-new-state job task-states)]
        (update-state-and-fire-if-changed (:id job) new-state)))))


(defn initialize [])

;#### debug ###################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
