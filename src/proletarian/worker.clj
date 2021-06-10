(ns proletarian.worker
  (:require [proletarian.db :as db]
            [proletarian.executor :as executor]
            [proletarian.log :as log]
            [proletarian.protocols :as p]
            [proletarian.retry :as retry]
            [proletarian.transit :as transit])
  (:import (javax.sql DataSource)
           (java.time Instant Clock)))

(defn process-next-job!
  "Gets the next job from the database table and runs it.

   This function is part of the internal machinery of the Proletarian worker, but is being exposed as a public function
   for use in testing scenarios and in the REPL. No default values are provided for any of the arguments or
   configuration options. See the documentation for, and implementation of, [[create-queue-worker]] for what those
   default values are. It might be a good idea to create a wrapper function around this function, for use in your own
   application, that provides sensible values for all the arguments and config.

   ### Arguments
   * `data-source` – a [javax.sql.DataSource](https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/javax/sql/DataSource.html)
       factory for creating connections to the PostgreSQL database.
   * `queue` – a keyword with the name of the queue.
   * `handler-fn` – the function that will be called when a job is pulled off the queue. It should be an arity-2
       function or multimethod. The first argument is the job type (as provided to [[proletarian.job/enqueue!]]). The
       second argument is the job's payload (again, as provided to [[proletarian.job/enqueue!]]).
   * `log` - a logger function that Proletarian calls whenever anything interesting happens during operation. It takes
       one or two arguments: The first is a keyword identifying the event being logged. The second is a map with data
       describing the event.
   * `config` – a map describing configuration options, see below.

   ### Config
   * `:proletarian.db/job-table` – which PostgreSQL table to write the job to.
   * `:proletarian.db/archived-job-table` – which PostgreSQL table to write archived jobs to.
   * `:proletarian.db/serializer` – an implementation of the [[proletarian.protocols/Serializer]] protocol. You should
       use the same serializer for [[proletarian.job/enqueue!]].
   * `:proletarian.worker/clock` – a [java.time.Clock](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/time/Clock.html)
       instance to use for getting the current time.

   Returns true if there was a job to be run, and the current thread did not receive an interrupt while handling the
   job. Returns false if there was an interrupt.
   Returns nil if there was no job to be run."
  [data-source queue handler-fn log config]
  (db/with-tx data-source
    (fn [conn]
      (when-let [job (db/get-next-job conn config queue)]
        (let [{:proletarian.job/keys [job-id job-type payload attempts] :as job}
              (update job :proletarian.job/attempts inc)

              clock (::clock config)
              log (log/wrap log {:job-id job-id :job-type job-type :attempt attempts})]
          (try
            (log ::handling-job)
            (handler-fn job-type payload)
            (log ::job-finished)
            (db/archive-job! conn config job-id :success (Instant/now clock))
            (db/delete-job! conn config job-id)
            (catch InterruptedException _
              (log ::job-interrupted)
              (.interrupt (Thread/currentThread)))
            (catch Exception e
              (log ::handle-job-exception {:exception e})
              (retry/maybe-retry! conn config job e log))))
        (not (Thread/interrupted))))))

(defn ^:private process-next-jobs!
  "Gets the next job from the database table and runs it. When the job is finished, loops back and tries to get a new
   job from the database. Returns when no jobs are available for processing."
  [data-source queue handler-fn log stop-queue-worker! config]
  (try
    (let [log (log/wrap log {:worker-thread-id (::worker-thread-id config)})]
      (log ::polling-for-jobs)
      (loop []
        (when (process-next-job! data-source queue handler-fn log config)
          (recur))))
    (catch InterruptedException _
      (log ::worker-interrupted)
      (stop-queue-worker!))
    (catch Throwable e
      (log ::job-worker-error {:throwable e})
      (stop-queue-worker!))))

(defn ^:private create-shutdown-hook
  [worker]
  (Thread.
    ^Runnable
    (fn []
      (try
        (p/stop! worker)
        (catch InterruptedException e
          (.printStackTrace e)
          (.interrupt (Thread/currentThread)))))))

(defn ^:private install-jvm-shutdown-hook!
  [worker hook]
  (reset! hook (create-shutdown-hook worker))
  (.addShutdownHook (Runtime/getRuntime) @hook))

(defn ^:private remove-shutdown-hook!
  [hook]
  (try
    (.removeShutdownHook (Runtime/getRuntime) @hook)
    (catch IllegalStateException _
      ;; JVM is shutting down, ignore.
      )
    (finally
      (reset! hook nil))))

(defn create-queue-worker
  "Create and return a Queue Worker, which is an instance of [[proletarian.protocols/QueueWorker]]. After creation, the
   Queue Worker must be started using [[start!]], and can be stopped using [[stop!]].

   ### Arguments
   * `data-source` – a [javax.sql.DataSource](https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/javax/sql/DataSource.html)
       factory for creating connections to the PostgreSQL database.
   * `handler-fn` – the function that will be called when a job is pulled off the queue. It should be an arity-2
       function or multimethod. The first argument is the job type (as provided to [[proletarian.job/enqueue!]]). The
       second argument is the job's payload (again, as provided to [[proletarian.job/enqueue!]]).
   * `options` – an optional map describing configuration options, see below.

   ### Options
   The optional third argument is an options map with the following keys, all optional with default values:
   * `:queue` – a keyword with the name of the queue. The default value is `:proletarian/default`.
   * `:job-table` – which PostgreSQL table to write the job to. The default is `proletarian.job`. You should only have
       to override this if you changed the default table name during installation.
   * `:archived-job-table` – which PostgreSQL table to write archived jobs to. The default is
       `proletarian.archived_job`. You should only have to override this if you changed the default table name during
       installation.
   * `:serializer` – an implementation of the [[proletarian.protocols/Serializer]] protocol. The default is a Transit
       serializer (see [[proletarian.transit/create-serializer]]). If you override this, you should use the same
       serializer for [[proletarian.job/enqueue!]].
   * `:log` - a logger function that Proletarian calls whenever anything interesting happens during operation. It takes
       one or two arguments: The first is a keyword identifying the event being logged. The second is a map with data
       describing the event. The default logging function is simply a println-logger that will print every event using
       `println`.
   * `:queue-worker-id` – a string identifying this Queue Worker. It is used as a thread prefix for names of threads in
       the thread pool. It is also added to the log event data under the key `:proletarian.worker/queue-worker-id`. The
       default value is computed from the name of the queue that this worker is getting jobs from.
   * `:polling-interval-ms` – the time in milliseconds to wait after a job is finished before polling for a new one. The
       default value is 100 milliseconds.
   * `:worker-threads` - the number of worker threads that work in parallel. The default value is 1.
   * `:await-termination-timeout-ms` – the time in milliseconds to wait for jobs to finish before throwing an error when
       shutting down the thread pool. The default value is 10000 (10 seconds).
   * `:install-jvm-shutdown-hook?` – should Proletarian install a JVM shutdown hook that tries to stop the Queue Worker
       (using [[stop!]]) when the JVM is shut down? The default is `false`.
   * `:on-shutdown` – a function that Proletarian calls after the Queue Worker has shut down successfully. It takes no
       arguments, and the return value is discarded. The default function is a no-op.
   * `:clock` – the [[java.time.Clock]] to use for getting the current time. Used in testing. The default is
       [[java.time.Clock/systemUTC]]."
  ([data-source handler-fn] (create-queue-worker data-source handler-fn nil))
  ([data-source handler-fn {:proletarian/keys [queue job-table archived-job-table serializer log queue-worker-id
                                               polling-interval-ms worker-threads await-termination-timeout-ms
                                               install-jvm-shutdown-hook? on-shutdown
                                               clock]
                            :or {queue db/DEFAULT_QUEUE
                                 job-table db/DEFAULT_JOB_TABLE
                                 archived-job-table db/DEFAULT_ARCHIVED_JOB_TABLE
                                 serializer (transit/create-serializer)
                                 log log/println-logger
                                 polling-interval-ms 100
                                 worker-threads 1
                                 await-termination-timeout-ms 10000
                                 install-jvm-shutdown-hook? false
                                 on-shutdown (fn [])
                                 clock (Clock/systemUTC)}}]
   {:pre [(instance? DataSource data-source)]}
   (let [queue-worker-id (or (some-> queue-worker-id str) (str "proletarian[" queue "]"))
         log (log/wrap log {::queue-worker-id queue-worker-id})
         executor (atom nil)
         shutdown-hook (atom nil)
         config {::db/job-table job-table
                 ::db/archived-job-table archived-job-table
                 ::db/serializer serializer
                 ::queue-worker-id queue-worker-id
                 ::worker-threads worker-threads
                 ::polling-interval-ms polling-interval-ms
                 ::await-termination-timeout-ms await-termination-timeout-ms
                 ::clock clock}]
     (reify p/QueueWorker
       (start! [this]
         (when-not @executor
           (when install-jvm-shutdown-hook? (install-jvm-shutdown-hook! this shutdown-hook))
           (let [{::keys [queue-worker-id worker-threads polling-interval-ms]} config
                 stop-queue-worker! #(future
                                       (try
                                         (p/stop! this)
                                         (catch Throwable e
                                           (log ::queue-worker-shutdown-error {:throwable e}))))
                 work! (fn [worker-thread-id]
                         (process-next-jobs! data-source queue handler-fn log stop-queue-worker!
                                             (assoc config
                                               ::worker-thread-id worker-thread-id)))]
             (reset! executor (executor/create-scheduled-executor worker-threads queue-worker-id))
             (dotimes [i worker-threads]
               (executor/schedule @executor (partial work! (inc i)) polling-interval-ms)
               ;; Add some jitter to the worker threads:
               ;; Sleep at least 100 ms, but no more than 1000 ms, before
               ;; scheduling the next worker thread.
               (Thread/sleep (+ 100 (rand-int (min 900 polling-interval-ms)))))
             true)))
       (stop! [_]
         (when @executor
           (when install-jvm-shutdown-hook? (remove-shutdown-hook! shutdown-hook))
           (let [{::keys [await-termination-timeout-ms]} config]
             (executor/shutdown-executor @executor await-termination-timeout-ms log)
             (on-shutdown)
             true)))))))

(defn start!
  "Start the Queue Worker."
  [queue-worker]
  (p/start! queue-worker))

(defn stop!
  "Stop the Queue Worker."
  [queue-worker]
  (p/stop! queue-worker))
