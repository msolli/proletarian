(ns proletarian.transit
  (:require [cognitect.transit :as transit]
            [proletarian.protocols :as p])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.time Instant)))

;; java.time.Instant

(def instant-writer
  (transit/write-handler
    (constantly "m")
    (fn [v] (-> ^Instant v .toEpochMilli))
    (fn [v] (str (-> ^Instant v .toEpochMilli)))))


(def instant-reader
  (transit/read-handler
    (fn [o]
      (let [millis (Long/parseLong o)]
        (Instant/ofEpochMilli millis)))))


(def default-write-handlers
  {Instant   instant-writer})

(def default-read-handlers
  {"m"          instant-reader})

(defn encode
  ([data]
   (encode data {}))
  ([data write-handlers]
   (let [out (ByteArrayOutputStream. 4096)
         writer (transit/writer out :json {:handlers (merge default-write-handlers write-handlers)})]
     (transit/write writer data)
     (.toString out "UTF-8"))))

(defn decode
  ([^String s]
   (decode s {}))
  ([^String s read-handlers]
   (let [in (-> s
                (.getBytes "UTF-8")
                (ByteArrayInputStream.))
         reader (transit/reader in :json {:handlers (merge default-read-handlers read-handlers)})]
     (transit/read reader))))

(defn create-serializer
  []
  (reify p/Serializer
    (encode [_ data] (encode data))
    (decode [_ data-string] (decode data-string))))
