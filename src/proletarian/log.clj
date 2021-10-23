(ns proletarian.log
  {:no-doc true})

(defn println-logger
  [x data]
  (if (some? data)
    (println x data)
    (println x)))

(defn wrap
  [log context]
  (fn
    ([x] (log x context))
    ([x data] (log x (merge data context)))))

