(ns build
  "Proletarian build script.

   Release process:
   1. Run `clojure -T:build next-version` to get the upcoming version number.
   2. Update CHANGELOG.md and README.md installation examples with that version.
   3. Commit the changes (this commit will have the predicted version).
   4. Preview docs: run `make cljdoc.run` in one terminal, then
      `make cljdoc.import` in another. Check
      localhost:8000/d/msolli/proletarian/<version>. Amend and re-import if
      needed.
   5. Run `env CLOJARS_USERNAME=msolli CLOJARS_PASSWORD=<token> make deploy` to tag, push the tag, and deploy to Clojars.
   6. Run `git push` to push the release commit.

   Available tasks:
     clojure -T:build clean
     clojure -T:build jar
     clojure -T:build install
     clojure -T:build deploy
     clojure -T:build version
     clojure -T:build next-version"
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'msolli/proletarian)
(def the-version (format "1.0.%s" (b/git-count-revs nil)))
(def tag (str "v" the-version))
(def class-dir "target/classes")
(def jar-file (format "target/proletarian-%s.jar" the-version))
(def src-pom "build/pom.xml")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  "Delete the target directory."
  [_]
  (b/delete {:path "target"})
  (println "Cleaned target/"))

(defn jar
  "Build the JAR file."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version the-version
                :basis @basis
                :src-pom src-pom
                :src-dirs ["src"]})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Built" jar-file))

(defn install
  "Install the JAR to the local Maven repository (~/.m2)."
  [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version the-version
              :jar-file jar-file
              :class-dir class-dir})
  (println "Installed" lib the-version "to local Maven repository"))

(defn deploy
  "Build the JAR, tag the release, push the tag, and deploy to Clojars."
  [_]
  (jar nil)
  (b/git-process {:git-args ["tag" "-a" tag "-m" (str "Version " the-version)]})
  (b/git-process {:git-args ["push" "origin" tag]})
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Deployed" lib the-version "to Clojars"))

(defn version
  "Print the current version."
  [_]
  (println the-version))

(defn next-version
  "Print the predicted next version (what the next commit will have)."
  [_]
  (let [next-count (inc (parse-long (b/git-count-revs nil)))]
    (println (format "1.0.%d" next-count))))
