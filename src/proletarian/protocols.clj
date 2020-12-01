(ns proletarian.protocols)

(defprotocol WorkerController
  "The process that runs the job workers for a particular queue."
  (start! [_]
    "Sets up a thread pool and starts polling for jobs.")
  (stop! [_]
    "Shuts down the job workers and the thread pool. Will allow running jobs
     to complete before shutting down the thread pool."))

(defprotocol Serializer
  (encode [_ data])
  (decode [_ data-string]))
