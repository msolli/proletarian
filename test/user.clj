(ns user
  (:require [kaocha.repl :refer [run]]
            [puget.printer]))

(defn add-pretty-print-tap!
  []
  (add-tap (bound-fn* puget.printer/cprint)))
