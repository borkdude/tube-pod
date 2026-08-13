(ns tube-pod.app
  (:require [babashka.fs :as fs]
            [babashka.http-server :as http-server]
            [babashka.nrepl.server :as nrepl]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [split.core :refer [client defpart defui server]]
            [split.server :as split]
            [tube-pod.feed :as feed]))

(def base-url (or (System/getenv "TUBE_POD_URL") "http://10.0.1.11:8088"))

(defonce state
  (atom {:library []    ; episodes on disk, newest first
         :jobs {}}))   ; id -> {:url :status :error}

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

(defn sync!
  "ffprobe is slow enough to be worth doing once per change rather than per
  render, so the library is cached and the feed rewritten at the same time."
  []
  (swap! state assoc :library (mapv episode (feed/files)))
  (feed/write! base-url))

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

;; `playing` is marked `^:server`, so it is substituted rather than bound in the
;; browser. As an ordinary parameter the atom would land in browser scope and be
;; shipped back as an rpc argument.
(defpart episode-row [{:keys [id title author duration added]} current ^:server playing]
  [:li.episode {:key id :class (when (= id current) "playing")}
   [:button.play {:on-click (fn [_] (server (reset! playing (client id))))} "▶"]
   [:div.meta
    [:span.title title]
    [:span.sub author " · " duration " · " added]]
   [:button.del {:on-click (fn [_] (server (delete! (client id))))} "×"]])

(defpart job-row [{:keys [id url status error]}]
  [:li.job {:key id}
   [:span.status status]
   [:span.sub (or error url)]
   (when error
     [:button.del {:on-click (fn [_] (server (dismiss! (client id))))} "×"])])

(defui admin [playing]
  (let [episodes (server (:library @state))
        running  (server (mapv (fn [[id j]] (assoc j :id id)) (:jobs @state)))
        total    (server (count (:library @state)))
        current  (server @playing)]
    [:div
     [:h1 "tube-pod"]
     [:input.add {:placeholder "youtube url, then Enter"
                  :autofocus true
                  :on-key-down (fn [e]
                                 (when (= "Enter" (.-key e))
                                   (server (add! (client (.. e -target -value))))
                                   (set! (.. e -target -value) "")))}]
     ;; `when` renders nil as a placeholder node rather than nothing, so the
     ;; player keeps its position and a patch elsewhere does not disturb it.
     (when current
       [:audio.player {:src (str "/audio/" current ".m4a")
                       :controls true
                       :autoplay true}])
     (when (seq running)
       [:ul.jobs (for [j running] (job-row j))])
     [:p.count total " episodes · " [:a {:href "/feed.xml"} "feed.xml"]]
     [:ul.episodes (for [ep episodes] (episode-row ep current playing))]]))

;; the library and the download queue are shared, what is playing belongs to
;; whoever opened the panel
(def ui
  (split/handler {:index "public/index.html"
                  :watch [state]
                  :mounts [{:el "app"
                            :state (fn [] {:playing (atom nil)})
                            :component (fn [{:keys [playing]}] (admin playing))}]}))

;; The panel takes the routes it owns, the feed and the audio come from
;; http-server, and this decides the order.
(defn app [req]
  (or (ui req) (files req)))

(defn -main [& args]
  (sync!)
  (http/run-server app {:port 8088})
  (println "admin: http://localhost:8088")
  (println (str "feed:  " base-url "/feed.xml"))
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1667})
    (println "nrepl://localhost:1667"))
  @(promise))
