(ns proletarian.log)

(defn println-logger
  ([x]
   (println x))
  ([x data]
   (println x data)))

(defn wrap
  [log context]
  (fn
    ([x] (log x context))
    ([x data] (log x (merge data context)))))

