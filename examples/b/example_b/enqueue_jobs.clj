(ns example-b.enqueue-jobs
  "In this example we're going to enqueue some jobs that will fail some of the
   time. The jobs will be retried by the workers according to their retry
   strategy.

   This example also illustrates use of the context. The first parameter to the
   handle-job! multimethod is context. This is a map that you provide when
   creating the worker controller, using the :proletarian/context-fn option,
   which is then passed into the jobs when they are run. It should contain
   references to stateful objects and functions that you need for the job to do
   its work. Examples of this could be things like database and other
   (Elasticsearch, Redis) connections, and runtime configuration.

   Proletarian pre-populates the context with the current PostgreSQL transaction
   object under the key :proletarian/tx. This object is a java.sql.Connection
   object. It's useful if you want to ensure that writes to the database in the
   job handler succeeds or fails together with the job itself."

  (:require [examples]
            [next.jdbc :as jdbc]
            [proletarian.job :as job]
            [puget.printer :as puget]))

(defn run
  [_]
  (let [ds (jdbc/get-datasource (:jdbc-url examples/config))]
    (examples/preamble ds)
    (let [conn (jdbc/get-connection ds)
          job-type ::sometimes-failing]
      (loop [batch-no 1]
        (println "Adding 10 new jobs to :proletarian/default queue:")
        (dotimes [i 10]
          (let [payload {:batch-no batch-no
                         :counter i}
                job-id (job/enqueue! conn job-type payload)]
            (puget/cprint {:job-id job-id
                           :job-type ::echo
                           :payload payload})))
        (println)
        (println "Press Enter to enqueue more jobs (Ctrl-C to exit)")
        (read-line)
        (recur (inc batch-no))))))

(defmethod job/handle-job! ::sometimes-failing
  [{:keys [do-possibly-failing-thing!]} _job-type payload]
  (let [{:keys [batch-no counter]} payload
        log #(puget/cprint [(symbol (format "%3d/%1d" batch-no counter)) %])]
    (log (str "Running job " ::echo ". Payload:"))
    (log payload)
    (log "This will fail 50 % of the time and take on average a second:")
    (do-possibly-failing-thing!)
    (log "Phew, it didn't fail. Done.")))

(defmethod job/retry-strategy ::sometimes-failing
  [_job exception]
  (let [retry-after (-> exception (ex-data) :retry-after)]
    {:retries 2
     :delays [retry-after]}))
