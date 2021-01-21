(ns proletarian.retry
  (:require [proletarian.db :as db]
            [proletarian.job :as job])
  (:import (java.time Instant)))

(defn valid-retry-strategy?
  [{:keys [retries delays] :as rs}]
  (and
    (map? rs)
    (nat-int? retries)
    (or (nil? delays)
        (and
          (vector? delays)
          (every? nat-int? delays)
          (nat-int? (count delays))))))

(defn valid-job-attempts?
  [{:proletarian.job/keys [attempts]}]
  (pos-int? attempts))

(defn retry-data
  "Convert a retry strategy to a concrete retry specification for a job. This
   is a map with keys :retries-left and :retry-at.

   :retries-left is the number of retries left
   :retry-at is the time at which the next retry should be attempted

   This function is defined for valid retry strategies, and for jobs with
   attempts greater than zero."
  [retry-strategy job clock]
  {:pre [(valid-retry-strategy? retry-strategy)
         (valid-job-attempts? job)]}
  (let [attempts (::job/attempts job)
        retries (:retries retry-strategy)
        delays (:delays retry-strategy)
        retries-left (if (zero? retries)
                       0
                       (- (inc retries) attempts))
        retry-at (.plusMillis (Instant/now clock)
                              (if (empty? delays)
                                0
                                (get delays (dec attempts) (nth delays (dec (count delays))))))]
    (cond-> {:retries-left retries-left}
            (< 0 retries-left) (assoc :retry-at retry-at))))

(defn maybe-retry!
  [conn config job e log]
  (let [job-id (:proletarian.job/job-id job)
        clock (:proletarian.worker/clock config)
        retry-spec (some-> (job/retry-strategy job e) (retry-data job clock))
        finished-at (Instant/now clock)]
    (if (pos-int? (:retries-left retry-spec))
      (let [{:keys [retries-left retry-at]} retry-spec]
        (log ::retrying {:retry-at retry-at :retries-left retries-left})
        (db/retry-at! conn config (:proletarian.job/job-id job) retry-at))
      (do
        (log ::not-retrying {:retry-spec retry-spec})
        (db/archive-job! conn config job-id :failure finished-at)
        (db/delete-job! conn config job-id)))))
