(ns example-b.enqueue-jobs
  "In this example we're going to enqueue some jobs that will fail some of the
   time. The jobs will be retried by the workers according to their retry
   strategy."

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

(defn do-possibly-failing-thing!
  "Function that sleeps between 500 and 1500 ms, and then throws an exception in about every other invocation.
   If it throws, the exception will have data with the key :retry-after, which is the number of milliseconds after which
   the operation could be retried. This mirrors a common backoff technique in web APIs."
  []
  (Thread/sleep (+ 500 (rand-int 1000)))
  (when (zero? (rand-int 2))
    (throw (ex-info "This operation failed for some reason" {:retry-after (rand-int 1000)}))))

(defn handle-job!
  [_job-type payload]
  (let [{:keys [batch-no counter]} payload
        log #(puget/cprint [(symbol (format "%3d/%1d" batch-no counter)) %])]
    (log (str "Running job " ::echo ". Payload:"))
    (log payload)
    (log "This will fail 50 % of the time and take on average a second:")
    (do-possibly-failing-thing!)
    (log "Phew, it didn't fail. Done.")))

(defn retry-strategy
  [_job exception]
  (let [retry-after (-> exception (ex-data) :retry-after)]
    {:retries 2
     :delays [retry-after]}))

(defn handle-failed-job!
  [{:proletarian.job/keys [payload attempts] :as _job} ^Exception exception]
  (let [{:keys [batch-no counter]} payload
        log #(puget/cprint [(symbol (format "%3d/%1d" batch-no counter)) %] {:width 120})]
    (log (str "Job failed after " attempts " attempts (exception message: '" (.getMessage exception) "')"))))
