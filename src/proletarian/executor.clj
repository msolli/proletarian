(ns proletarian.executor
  {:no-doc true}
  (:import
    (java.util.concurrent Executors RejectedExecutionException ScheduledExecutorService ThreadFactory TimeUnit)))

(defn ^ThreadFactory create-thread-factory
  [thread-name-prefix]
  (let [thread-number (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (Thread. runnable (str thread-name-prefix "-" (swap! thread-number inc)))))))

(defn ^ScheduledExecutorService create-scheduled-executor
  [pool-size thread-name-prefix]
  (let [thread-factory (create-thread-factory thread-name-prefix)]
    (Executors/newScheduledThreadPool (int pool-size) thread-factory)))

(defn ^ScheduledExecutorService create-single-thread-scheduled-executor
  [thread-name-prefix]
  (let [thread-factory (create-thread-factory thread-name-prefix)]
    (Executors/newSingleThreadScheduledExecutor thread-factory)))

(defn schedule
  [executor runnable interval-ms]
  (try
    (.scheduleWithFixedDelay executor runnable 0 interval-ms TimeUnit/MILLISECONDS)
    (catch RejectedExecutionException e
      ;; If executor is shutdown, this exception is expected.
      (when-not (.isShutdown executor)
        (throw e)))))

(defn shutdown-executor
  [executor await-termination-timeout-ms log]
  (if (.isShutdown executor)
    (log ::already-shut-down)
    (try
      (log ::shutting-down)
      ;; Disable new tasks from being scheduled
      (.shutdown executor)
      ;; Cancel currently running tasks
      (.shutdownNow executor)
      ;; Wait a while for tasks to respond to being cancelled
      (if-not (.awaitTermination executor await-termination-timeout-ms TimeUnit/MILLISECONDS)
        (throw (Exception. "Could not shut down executor service properly"))
        (log ::completed-shutdown))
      (catch InterruptedException _
        (log ::interrupted-while-shutting-down)
        ;; Re-cancel if current thread also interrupted
        (.shutdownNow executor)
        ;; Preserve interrupt status
        (.interrupt (Thread/currentThread))))))
