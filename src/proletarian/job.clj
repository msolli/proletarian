(ns proletarian.job
  (:require [proletarian.db :as db]
            [proletarian.protocols :as p]
            [proletarian.transit :as transit]
            [proletarian.uuid.postgresql :as pg-uuid])
  (:import (java.sql Connection)
           (java.time Clock Duration Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(defn ^:no-doc ->process-at
  "Calculate the effective process-at value."
  [^Instant now ^Instant process-at ^Duration process-in]
  (cond
    (and (nil? process-at) (nil? process-in))
    now

    (some? process-at)
    (if (.isAfter process-at now)
      process-at
      now)

    :else
    (if (.isNegative process-in)
      now
      (.addTo process-in now))))

(defn enqueue!
  "Enqueue a job in the Proletarian job queue.

   Returns the job-id (a UUID) of the enqueued job.

   ### Arguments
   * `conn` – a [[java.sql.Connection]] database connection.
   * `job-type` – a keyword that identifies the job type. The job type will be passed to your handler function (see
       second argument to [[proletarian.worker/create-queue-worker]]) as the first argument. Optionally implement the
       [[retry-strategy]] multimethod for this keyword to describe the retry strategy for this job type.
   * `payload` – the data that the job needs. It will be encoded and decoded using the serializer (see Options, below).
       The payload will be passed to your handler function as the second argument.
   * `options` – (optional) keyword arguments or a map describing configuration options, see below.

   ### Options
   The following two keys control when a job is processed:
   * `:process-at` - a [[java.time.Instant]] for when the job should be processed.
   * `:process-in` - a [[java.time.Duration]] for when the job should be processed. The value is added to the current
       time to get the actual time for processing.

   If both keys are provided, `:process-at` takes precedence.

   The following keys are configuration options, all optional with default values:
   * `:proletarian/queue` – a keyword with the name of the queue. The default value is `:proletarian/default`.
   * `:proletarian/job-table` – which PostgreSQL table to write the job to. The default is `proletarian.job`. You should
       only have to override this if you changed the default table name during installation.
   * `:proletarian/serializer` – an implementation of the [[proletarian.protocols/Serializer]] protocol. The default is
       a Transit serializer (see [[proletarian.transit/create-serializer]]). If you override this, you should use the
       same serializer for [[proletarian.worker/create-queue-worker]].
   * `:proletarian/uuid-fn` – a function for generating UUIDs. Used in testing. The default is
       [[java.util.UUID/randomUUID]].
   * `:proletarian/uuid-serializer` - an implementation of the `[[proletarian.protocols/UuidSerializer]] protocol.
       Its role is to help in the serializing and deserializing of UUIDs to accomodate various database
       requirements. It defaults to `proletarian.uuid.postgresql/create-serializer`. A `proletarian.uuid.mysql/create-serializer`
       is available if you wish to use MySQL with this library. If you override the default, you should use the same
       serializer for [[proletarian.worker/create-queue-worker]].
   * `:proletarian/clock` – the [[java.time.Clock]] to use for getting the current time. Used in testing. The default is
       [[java.time.Clock/systemUTC]]."
  [conn job-type payload & {:keys [process-at process-in]
                            :proletarian/keys [queue job-table serializer uuid-fn uuid-serializer clock]
                            :or {queue db/DEFAULT_QUEUE
                                 job-table db/DEFAULT_JOB_TABLE
                                 serializer (transit/create-serializer)
                                 uuid-fn (fn [] (UUID/randomUUID))
                                 uuid-serializer (pg-uuid/create-serializer)
                                 clock (Clock/systemUTC)}}]
  {:pre [(instance? Connection conn)]}
  (let [job-id (uuid-fn)
        now (Instant/now clock)]
    (assert (instance? UUID job-id))
    (assert (satisfies? p/Serializer serializer))
    (db/enqueue! conn
                 {::db/job-table job-table, ::db/serializer serializer ::db/uuid-serializer uuid-serializer}
                 {::job-id job-id
                  ::queue queue
                  ::job-type job-type
                  ::payload payload
                  ::attempts 0
                  ::enqueued-at now
                  ::process-at (->process-at now process-at process-in)})
    job-id))
