(ns tube-pod.feed
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def audio-dir "audio")
(def feed-file "feed.xml")

(defn probe [file]
  (-> (shell {:out :string}
             "ffprobe" "-v" "quiet" "-print_format" "json" "-show_format" (str file))
      :out
      (json/parse-string true)
      :format))

(defn escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn pub-date [file]
  (-> (fs/last-modified-time file)
      .toInstant
      (.atZone (java.time.ZoneId/systemDefault))
      (.format java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)))

(defn hms [seconds]
  (let [s (long (parse-double seconds))]
    (format "%02d:%02d:%02d" (quot s 3600) (rem (quot s 60) 60) (rem s 60))))

(defn files
  "Audio files, newest first."
  []
  (->> (fs/glob audio-dir "*.m4a")
       (sort-by #(fs/last-modified-time %))
       reverse))

(defn episode [base-url file]
  (let [{:keys [tags duration size]} (probe file)
        {:keys [title artist synopsis comment]} tags
        name (fs/file-name file)]
    (str "    <item>\n"
         "      <title>" (escape title) "</title>\n"
         "      <itunes:author>" (escape artist) "</itunes:author>\n"
         "      <description>" (escape (str synopsis "\n\n" comment)) "</description>\n"
         "      <link>" (escape comment) "</link>\n"
         "      <guid isPermaLink=\"false\">" (escape (fs/strip-ext name)) "</guid>\n"
         "      <pubDate>" (pub-date file) "</pubDate>\n"
         "      <itunes:duration>" (hms duration) "</itunes:duration>\n"
         "      <enclosure url=\"" base-url "/" audio-dir "/" name "\""
         " length=\"" size "\" type=\"audio/x-m4a\"/>\n"
         "    </item>\n")))

(defn feed [base-url files]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">\n"
       "  <channel>\n"
       "    <title>tube-pod</title>\n"
       "    <link>" base-url "/" feed-file "</link>\n"
       "    <description>Talks queued for offline listening.</description>\n"
       "    <language>en</language>\n"
       "    <itunes:author>tube-pod</itunes:author>\n"
       "    <itunes:explicit>false</itunes:explicit>\n"
       (str/join (map #(episode base-url %) files))
       "  </channel>\n"
       "</rss>\n"))

(defn write! [base-url]
  (let [fs (files)]
    (spit feed-file (feed base-url fs))
    (count fs)))
