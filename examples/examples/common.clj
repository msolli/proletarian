(ns examples.common
  (:require [next.jdbc :as jdbc]
            [proletarian.worker :as worker]))

(def config {:jdbc-url (or (System/getenv "DATABASE_URL")
                           "jdbc:postgresql://localhost/proletarian?user=proletarian&password=proletarian")})

(defn preamble
  [ds]
  (let [default-count (-> (jdbc/execute-one! ds ["select count(*) from proletarian.job where queue = ?"
                                                 (str :proletarian/default)])
                          :count)
        job-count (-> (jdbc/execute-one! ds ["select count(*) from proletarian.job"]) :count)
        archived-job-count (-> (jdbc/execute-one! ds ["select count(*) from proletarian.archived_job"]) :count)]
    (println "Number of jobs in :proletarian/default queue:" default-count)
    (println "Number of jobs in proletarian.jobs table:" job-count)
    (println "Number of jobs in proletarian.archived_jobs table:" archived-job-count)
    (when (or (< 0 job-count) (< 0 archived-job-count))
      (println "There are jobs in the tables already. If you want accurate stats in the end summary, you must run 'make examples.db.recreate' before running the examples."))
    (println)))

(defn summary
  [ds]
  (let [successful (-> (jdbc/execute-one! ds ["select count(*) from proletarian.archived_job where status = ?"
                                              (str :success)])
                       :count)
        failed (-> (jdbc/execute-one! ds ["select count(*) from proletarian.archived_job where status = ?"
                                          (str :failure)])
                   :count)]
    (println "Number of successful jobs:" successful)
    (println "Number of failed jobs:" failed)))

(defn add-shutdown-hook
  [worker]
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      ^Runnable
      (fn []
        (try
          (worker/stop! worker)
          (shutdown-agents)
          (catch InterruptedException e
            (.printStackTrace e)
            (.interrupt (Thread/currentThread))))))))
