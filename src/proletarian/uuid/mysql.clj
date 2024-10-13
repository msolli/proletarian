(ns proletarian.uuid.mysql
  (:require
   [proletarian.protocols :as p])
  (:import
   [java.util UUID]))

(defn ^:private to-byte-array
  "Given a UUID object, return a 16 byte array of its data."
  ^bytes [^UUID u]
  (let [lo (.getLeastSignificantBits u)
        hi (.getMostSignificantBits u)]
    (-> (java.nio.ByteBuffer/allocate 16)
        (.putLong hi)
        (.putLong lo)
        (.array))))

(defn ^:private from-longs
  "Given two longs, a high word and a low word, return a UUID object."
  ^java.util.UUID [^long hi ^long lo]
  (UUID. hi lo))

(defn ^:private from-byte-array
  "Given a 16 byte array, construct a UUID object from it."
  ^java.util.UUID [^bytes b]
  (let [bb (java.nio.ByteBuffer/wrap b)
        hi (.getLong bb)
        lo (.getLong bb)]
    (from-longs hi lo)))

;;
;; The recommended datatpye for UUIDs in MySQL 8.0.1 and above is to use a
;; BINARY(16) representation. Therefore, we need to convert the java.util.UUID
;; into an appropriate binary array for storage.
;;
(defn ^:private serialize
  [job-id]
  (to-byte-array job-id))

;;
;; Similarly, when reading the BINARY(16) value back, we need to convert the
;; byte array into an appropriate format so that a java.util.UUID can be
;; constructed.
;;
(defn ^:private deserialize
  [job-id]
  (from-byte-array job-id))

(defn create-serializer
  []
  (reify p/UuidSerializer
    (uuid-encode [_ job-id] (serialize job-id))
    (uuid-decode [_ job-id] (deserialize job-id))))
