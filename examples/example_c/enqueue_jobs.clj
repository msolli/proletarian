(ns example-c.enqueue-jobs
  (:require [examples.common :as common]
            [next.jdbc :as jdbc]
            [proletarian.job :as job]
            [puget.printer :as puget])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(defn run-1
  "Enqueue a blocking job that can be interrupted."
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url common/config))]
    (common/preamble ds)
    (println "Adding new blocking job to :proletarian/default queue:")
    (let [conn (jdbc/get-connection ds)
          job-type ::blocking-job
          payload {:sleep-ms  10000
                   :timestamp (Instant/now)}
          job-id (job/enqueue! conn job-type payload)]
      (puget/cprint {:job-id   job-id
                     :job-type ::blocking-job
                     :payload  payload}))))

(defn run-2
  "Enqueue a CPU-bound job that can not be interrupted."
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url common/config))]
    (common/preamble ds)
    (println "Adding new time-consuming job to :proletarian/default queue:")
    (let [conn (jdbc/get-connection ds)
          job-type ::cpu-bound-job
          payload {:run-ms    10000
                   :timestamp (Instant/now)}
          job-id (job/enqueue! conn job-type payload)]
      (puget/cprint {:job-id   job-id
                     :job-type ::blocking-job
                     :payload  payload}))))

(defmulti handle-job!
  "This multimethod is passed as an argument to create-queue-worker as the job handler function. It is called by the
   Proletarian poller when a job is ready for execution. Implement this multimethod for the different job types. The
   return value from your handler is discarded. If it throws, it is retried according to its retry strategy.

   If handle-job! is missing an implementation for a job type found in the job queue, it would result in an exception.
   Then the job would be retried according to the retry strategy given by the `:retry-strategy-fn` option (which
   defaults to no retries).

   Note that the worker in this example has been configured with `:proletarian/handler-fn-mode` set to `:advanced`.
   This means the handler-fn (this multimethod) accepts only one argument: a map with all the job details."
  (fn [job] (:proletarian.job/job-type job)))

(defmethod handle-job! :default
  [{:proletarian.job/keys [job-type payload]}]
  (throw (ex-info (format "handle-job! multimethod not implemented for job-type '%s'" job-type)
                  {:job-type job-type
                   :payload  payload})))

(defmethod handle-job! ::blocking-job
  [{{:keys [sleep-ms timestamp] :as payload} :proletarian.job/payload}]
  (println (str "Running job " ::blocking-job ". Payload:"))
  (puget/cprint payload)
  (println (str "Sleeping " sleep-ms "..."))
  (println "If you press Ctrl-C now, you should observe the following events:")
  (println " " :proletarian.executor/shutting-down)
  (println " " :proletarian.worker/job-interrupted)
  (println " " :proletarian.executor/completed-shutdown)
  (println)
  (println (format "The current job (timestamp: %s) should then be picked up\nagain when restarting the worker process."
                   timestamp))
  (println "If you don't interrupt, then the job will finish and won't be run again.")
  (println)
  (Thread/sleep ^long sleep-ms)
  (println "Done."))

(defmethod handle-job! ::cpu-bound-job
  [{{:keys [run-ms _timestamp] :as payload} :proletarian.job/payload}]
  (println (str "Running job " ::cpu-bound-job ". Payload:"))
  (puget/cprint payload)
  (println "This job is CPU-bound. We cannot interrupt/stop such a job. It will run until it
is finished, but the workers will not pick up any more jobs while it is shutting down.")
  (println (str "Running for " run-ms " ms..."))
  (println "If you press Ctrl-C now, you should observe the following events:")
  (println " " :proletarian.executor/shutting-down)
  (println "  (Pause until job finishes)")
  (println " " :proletarian.worker/job-finished)
  (println " " :proletarian.executor/completed-shutdown)
  (println)
  (let [start-time (System/currentTimeMillis)]
    (loop [t (System/currentTimeMillis)]
      (when (< t (+ start-time run-ms))
        (recur (System/currentTimeMillis)))))
  (println "Done."))
