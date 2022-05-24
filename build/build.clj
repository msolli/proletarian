(ns build
  "Proletarian build script.

   clojure -T:build jar

   For more information, run:
   clojure -A:deps -T:build help/doc"
  (:require
    [clojure.tools.build.api :as b]
    [org.corfield.build :as bb]))

(def lib 'msolli/proletarian)
(def version (format "1.0.%s-alpha" (b/git-count-revs nil)))
(def tag (str "v" version))

(defn jar
  [opts]
  (-> opts
      (assoc :lib lib :version version :tag tag :src-pom "build/pom.xml")
      (bb/clean)
      (bb/jar))
  (println "Current version:" version))

(defn install
  "Install the jar locally (into ~/.m2/repository/msolli/proletarian/)"
  [opts]
  (-> opts
      (assoc :lib lib :version version :tag tag)
      (bb/install)))

(defn current-git-version
  [& _]
  (println version))

(defn deploy
  "Deploy the jar to Clojars"
  [opts]
  (b/git-process {:git-args ["tag" "-a" tag "-m" (str "Version " version)]})
  (b/git-process {:git-args ["push" "origin" tag]})
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))
