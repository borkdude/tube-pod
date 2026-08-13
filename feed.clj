#!/usr/bin/env bb

;; Writes the feed and serves it, without the admin panel. `bb admin` does both
;; and adds the panel on 1341.

(require '[babashka.http-server :as http-server]
         '[tube-pod.feed :as feed])

(defn -main [& args]
  (let [base-url (or (first args) "http://10.0.1.11:8088")
        port (parse-long (or (second args) "8088"))]
    (println "wrote" feed/feed-file "with" (feed/write! base-url) "episodes")
    (println "feed url:" (str base-url "/" feed/feed-file))
    (http-server/serve {:port port :dir "."})
    @(promise)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
