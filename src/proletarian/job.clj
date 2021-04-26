(ns proletarian.job
  (:require [proletarian.db :as db]
            [proletarian.transit :as transit])
  (:import (java.sql Connection)
           (java.time Clock Instant)
           (java.util UUID)))

(defmulti retry-strategy
  "When a job throws an exception, it is caught by the Proletarian poller.
   This function is then called with the job and the caught exception. This
   multimethod dispatches on the job type. You can implement this method for
   your job type to have it retry any way you want based on the information in
   the job record and the caught exception.

   It should return a map that specifies the retry strategy:

   `:retries` The number of retries (note that the total number of attempts will
   be one larger than this number).
   `:delays` A vector of numbers of milliseconds to wait between retries.

   Do consider the polling interval and the job queue contention when planning
   your retry strategy. The retry delay should be thought of as the earliest
   time that the job will be retried. The actual retry time might be a little,
   or much, later, depending on the polling interval and what other jobs are in
   the queue before this one.

   Examples:
   ```clojure
   {:retries 2
    :delays [1000 5000]}
   ```
   This will retry two times. The first time after 1 second and the second
   after 5 seconds.

   ```clojure
   {:retries 4
    :delays [2000 10000]}
   ```
   This will retry four times. The first time after 2 seconds, and the last
   three times after 10 seconds."
  (fn [job _throwable]
    (::job-type job)))

;; The default retry strategy is to not retry.
(defmethod retry-strategy :default [_ _] nil)

(defn enqueue!
  "Enqueue a job in the Proletarian job queue.

   ### Arguments
   * `conn` – a [[java.sql.Connection]] database connection.
   * `job-type` – a keyword that identifies the job type. The job type will be passed to your handler function (see
       second argument to [[proletarian.worker/create-queue-worker]]) as the first argument. Optionally implement the
       [[retry-strategy]] multimethod for this keyword to describe the retry strategy for this job type.
   * `payload` – the data that the job needs. It will be encoded and decoded using the serializer (see Options, below).
       The payload will be passed to your handler function as the second argument.
   * `options` – an optional map describing configuration options, see below.

   ### Options
   The optional fourth argument is an options map with the following keys, all optional with default values:
   * `:queue` – a keyword with the name of the queue. The default value is `:proletarian/default`.
   * `:job-table` – which PostgreSQL table to write the job to. The default is `proletarian.job`. You should only have
       to override this if you changed the default table name during installation.
   * `:serializer` – a n implementation of the [[proletarian.protocols/Serializer]] protocol. The default is a Transit
       serializer (see [[proletarian.transit/create-serializer]]). If you override this, you should use the same
       serializer for [[proletarian.worker/create-queue-worker]].
   * `:uuid-fn` – a function for generating UUIDs. Used in testing. The default is [[java.util.UUID/randomUUID]].
   * `:clock` – the [[java.time.Clock]] to use for getting the current time. Used in testing. The default is
       [[java.time.Clock/systemUTC]]."
  ([conn job-type payload]
   (enqueue! conn job-type payload nil))
  ([conn job-type payload {:proletarian/keys [queue job-table serializer uuid-fn clock]
                           :or {queue db/DEFAULT_QUEUE
                                job-table db/DEFAULT_JOB_TABLE
                                serializer (transit/create-serializer)
                                uuid-fn (fn [] (UUID/randomUUID))
                                clock (Clock/systemUTC)}}]
   {:pre [(instance? Connection conn)]}
   (let [job-id (uuid-fn)
         now (Instant/now clock)]
     (db/enqueue! conn
                  {::db/job-table job-table, ::db/serializer serializer}
                  {::job-id job-id
                   ::queue queue
                   ::job-type job-type
                   ::payload payload
                   ::attempts 0
                   ::enqueued-at now
                   ::process-at now})
     job-id)))
