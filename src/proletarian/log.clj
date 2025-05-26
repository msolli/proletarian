(ns proletarian.log
  {:no-doc true}
  (:import (clojure.lang IDeref IFn)))

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

(defn ->null-logger
  []
  (let [calls (atom [])]
    (reify
      IFn
      (invoke [_ x data]
        (swap! calls conj [x data])
        nil)
      IDeref
      (deref [_]
        @calls))))
