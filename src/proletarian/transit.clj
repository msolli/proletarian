(ns proletarian.transit
  (:require [cognitect.transit :as transit]
            [proletarian.protocols :as p])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.time Instant)))

(set! *warn-on-reflection* true)

;; java.time.Instant

(def ^:private instant-writer
  (transit/write-handler
    (constantly "m")
    (fn [v] (-> ^Instant v .toEpochMilli))
    (fn [v] (str (-> ^Instant v .toEpochMilli)))))

(def ^:private instant-reader
  (transit/read-handler
    (fn [o]
      (let [millis (Long/parseLong o)]
        (Instant/ofEpochMilli millis)))))

(def ^:private default-write-handlers
  {Instant   instant-writer})

(def ^:private default-read-handlers
  {"m"          instant-reader})

(defn ^:private encode
  ([data]
   (encode data {}))
  ([data write-handlers]
   (let [out (ByteArrayOutputStream. 4096)
         writer (transit/writer out :json {:handlers (merge default-write-handlers write-handlers)})]
     (transit/write writer data)
     (.toString out "UTF-8"))))

(defn ^:private decode
  ([^String s]
   (decode s {}))
  ([^String s read-handlers]
   (let [in (-> s
                (.getBytes "UTF-8")
                (ByteArrayInputStream.))
         reader (transit/reader in :json {:handlers (merge default-read-handlers read-handlers)})]
     (transit/read reader))))

(defn create-serializer
  "Create a Transit serializer that implements the [[proletarian.protocols/Serializer]] protocol. This is the default
   serializer in Proletarian. It is used in [[proletarian.worker/create-queue-worker]] and
   [[proletarian.job/enqueue!]].

   It includes a read and write handler for [[java.time.Instant]]. If you need other custom handlers, you should
   implement [[proletarian.protocols/Serializer]] with your own functions for encoding and decoding."
  []
  (reify p/Serializer
    (encode [_ data] (encode data))
    (decode [_ data-string] (decode data-string))))
