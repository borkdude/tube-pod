(ns tube-pod.app
  (:require [babashka.fs :as fs]
            [babashka.http-server :as http-server]
            [babashka.nrepl.server :as nrepl]
            [babashka.process :as p]
            [buzz.core :as buzz :refer [client defpart defui local-state server server!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [tube-pod.feed :as feed]))

;; The address a podcast player uses. The panel runs here, the files live on the
;; other end of `remote`, so this is that machine and not this one.
(def base-url (or (System/getenv "TUBE_POD_URL") "http://10.0.1.11:8088"))

;; An rsync destination, such as root@my-vps:/srv/tube-pod. Without it nothing
;; is pushed and tube-pod serves its own files.
(def remote (System/getenv "TUBE_POD_REMOTE"))

(defonce state
  (atom {:library []    ; episodes on disk, newest first
         :jobs {}       ; id -> {:url :status :error}
         :push nil}))   ; {:status :error} of the last push

;; Each key is observed separately, so a download writing a progress line into
;; :jobs several times a second does not re-run the library slots with it.
(def state-source (buzz/atom-source state))

;; http-server's router is an ordinary Ring handler that already does Range
;; requests, which podcast clients and <audio> both need. Reaching through the
;; var because it is private: making `file-router` public would turn this into a
;; plain call.
(def ^:private files
  (#'http-server/file-router (fs/path ".") {}))

(defn- added [file]
  (-> (fs/last-modified-time file)
      .toInstant
      (.atZone (java.time.ZoneId/systemDefault))
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn- episode [file]
  (let [{:keys [tags duration]} (feed/probe file)]
    {:id       (str (fs/strip-ext (fs/file-name file)))
     :title    (:title tags)
     :author   (:artist tags)
     :duration (feed/hms duration)
     :added    (added file)}))

;; One push at a time, in the background, so a handler never waits on an upload.
;; An agent gives both: `send-off` queues, and the panel reads the result.
(defonce ^:private pusher (agent nil))

(defn- rsync [& args]
  (apply p/shell {:continue true :err :string :out :string} "rsync" args))

(defn- push-once [_]
  (try
    (swap! state assoc :push {:status "pushing"})
    ;; Audio first. A feed that names a file the server does not have yet is
    ;; worse than a feed that is a moment out of date.
    (let [audio (rsync "-az" "--delete" (str feed/audio-dir "/") (str remote "/" feed/audio-dir "/"))
          feed  (when (zero? (:exit audio))
                  (rsync "-az" feed/feed-file (str remote "/")))
          fail  (first (remove #(zero? (:exit %)) (remove nil? [audio feed])))]
      (swap! state assoc :push
             (if fail
               {:status "failed" :error (last (remove str/blank? (str/split-lines (str (:err fail)))))}
               {:status "ok"})))
    (catch Exception e
      (swap! state assoc :push {:status "failed" :error (ex-message e)})))
  nil)

(defn push! []
  (when remote (send-off pusher push-once)))

(defn sync!
  "ffprobe is slow enough to be worth doing once per change rather than per
  render, so the library is cached and the feed rewritten at the same time.
  The push happens after, in the background."
  []
  (swap! state assoc :library (mapv episode (feed/files)))
  (feed/write! base-url)
  (push!))

;; yt-dlp is given its arguments as a vector and the url after `--`, so nothing
;; typed into the browser can become a flag or reach a shell.

(defn- progress [line]
  (cond
    (re-find #"(\d+\.\d)%" line) (str (second (re-find #"(\d+\.\d)%" line)) "%")
    (str/includes? line "[ExtractAudio]") "converting"
    (str/includes? line "Destination") "downloading"))

(defn add! [url]
  (let [url (str/trim (str url))]
    (when (re-matches #"https?://\S+" url)
      (let [id (str (random-uuid))]
        (swap! state assoc-in [:jobs id] {:url url :status "starting"})
        (future
          (try
            ;; Prefer a format that is already m4a. `-x --audio-format m4a`
            ;; picks opus and re-encodes, which is slower, and the opus urls
            ;; currently come back 403.
            (let [proc (p/process ["yt-dlp" "-f" "bestaudio[ext=m4a]/bestaudio"
                                   "--embed-metadata" "--no-playlist" "--newline"
                                   "-o" (str feed/audio-dir "/%(id)s.%(ext)s")
                                   "--" url]
                                  {:out :stream :err :string})]
              (with-open [rdr (io/reader (:out proc))]
                (doseq [line (line-seq rdr)]
                  (when-let [p (progress line)]
                    (swap! state assoc-in [:jobs id :status] p))))
              (let [{:keys [exit err]} @proc]
                (if (zero? exit)
                  (do (swap! state update :jobs dissoc id)
                      (sync!))
                  (swap! state assoc-in [:jobs id]
                         {:url url
                          :status "failed"
                          :error (last (remove str/blank? (str/split-lines (str err))))}))))
            (catch Exception e
              (swap! state assoc-in [:jobs id] {:url url :status "failed" :error (ex-message e)}))))))))

(defn dismiss! [id]
  (swap! state update :jobs dissoc id))

(defn delete!
  "Removes one episode and its file. The id arrives from the browser, so the
  path it produces has to be checked to be inside the audio directory."
  [id]
  (let [root (fs/canonicalize feed/audio-dir)
        file (fs/path feed/audio-dir (str id ".m4a"))]
    (when (and (fs/exists? file)
               (fs/starts-with? (fs/canonicalize file) root))
      (fs/delete file)
      (sync!))))

;; `playing` is an ordinary parameter here: the atom is browser state, made by
;; `(local-state nil)` in `admin`, so setting it redraws without asking the server.
(defpart episode-row [{:keys [id title author duration added]} current playing]
  [:li.episode {:key id :class (when (= id current) "playing")}
   [:button.play {:on-click (fn [_] (reset! playing id))} "▶"]
   [:div.meta
    [:span.title title]
    [:span.sub author " · " duration " · " added]]
   [:button.del {:on-click (fn [_] (server! (delete! (client id))))} "×"]])

(defpart job-row [{:keys [id url status error]}]
  [:li.job {:key id}
   [:span.status status]
   [:span.sub (or error url)]
   (when error
     [:button.del {:on-click (fn [_] (server! (dismiss! (client id))))} "×"])])

(defui admin []
  (let [episodes (server (buzz/observe state-source [:library]))
        running  (server (mapv (fn [[id j]] (assoc j :id id))
                               (buzz/observe state-source [:jobs])))
        push     (server (buzz/observe state-source [:push]))
        playing  (local-state nil)
        current  @playing]
    [:div
     [:h1 "tube-pod"]
     [:input.add {:placeholder "youtube url, then Enter"
                  :autofocus true
                  :on-key-down (fn [e]
                                 (when (= "Enter" (.-key e))
                                   (server! (add! (client (.. e -target -value))))
                                   (set! (.. e -target -value) "")))}]
     ;; `when` renders nil as a placeholder node rather than nothing, so the
     ;; player keeps its position and a patch elsewhere does not disturb it.
     (when current
       [:audio.player {:src (str "/audio/" current ".m4a")
                       :controls true
                       :autoplay true}])
     (when (seq running)
       [:ul.jobs (for [j running] (job-row j))])
     [:p.count (count episodes) " episodes · " [:a {:href "/feed.xml"} "feed.xml"]
      (when push
        [:span {:class (when (= "failed" (:status push)) "failed")}
         " · " (or (:error push) (:status push))])]
     [:ul.episodes (for [ep episodes] (episode-row ep current playing))]]))

;; the library and the download queue are shared, what is playing belongs to
;; whoever opened the panel
(def ui
  (buzz/handler {:index "public/index.html"
                 :mounts [{:el "app" :ui #'admin}]}))

;; The panel takes the routes it owns, and the feed and the audio come from
;; http-server. `files` serves the working directory, so it gets only these two
;; paths. Without the check it also hands out src, bb.edn and .git.
(def ^:private public-path #"/(feed\.xml|audio/[^/]+\.m4a)")

(defn app [req]
  (or (ui req)
      (when (re-matches public-path (:uri req)) (files req))
      {:status 404 :body "not found"}))

(defn -main [& args]
  (sync!)
  ;; The panel authenticates nobody, so it listens only on this machine. Without
  ;; :ip http-kit takes every interface, which puts the handlers on whatever
  ;; network the laptop is on.
  (http/run-server app {:port 8088 :ip "127.0.0.1"})
  (println "admin: http://localhost:8088")
  (println (str "feed:  " base-url "/feed.xml"))
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1667})
    (println "nrepl://localhost:1667"))
  @(promise))
