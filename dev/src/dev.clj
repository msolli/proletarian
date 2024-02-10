(ns dev
  (:require
    [playback.core :as playback]))

(comment
  (do
    (playback/open-portal! {:launcher :intellij})
    (add-tap #'playback/portal-tap)))
