(ns proletarian.uuid.postgresql
  (:require
   [proletarian.protocols :as p]))

(defn ^:private serialize
  [job-id]
  job-id) ;; The PostgreSQL driver knows how to write a UUID natively.

(defn ^:private deserialize
  [job-id]
  job-id) ;; The PostgreSQL driver knows how to read a UUID natively (as java.util.UUID).

(defn create-serializer
  []
  (reify p/UuidSerializer
    (uuid-encode [_ job-id] (serialize job-id))
    (uuid-decode [_ job-id] (deserialize job-id))))
