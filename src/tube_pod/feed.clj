(ns tube-pod.feed
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.data.xml :as xml]))

(def audio-dir "audio")
(def feed-file "feed.xml")

(def itunes-ns "http://www.itunes.com/dtds/podcast-1.0.dtd")
(xml/alias-uri 'itunes itunes-ns)

(defn probe [file]
  (-> (shell {:out :string}
             "ffprobe" "-v" "quiet" "-print_format" "json" "-show_format" (str file))
      :out
      (json/parse-string true)
      :format))

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
    (xml/element
     :item {}
     (xml/element :title {} title)
     (xml/element ::itunes/author {} artist)
     (xml/element :description {} (str synopsis "\n\n" comment))
     (xml/element :link {} comment)
     (xml/element :guid {:isPermaLink "false"} (str (fs/strip-ext name)))
     (xml/element :pubDate {} (pub-date file))
     (xml/element ::itunes/duration {} (hms duration))
     (xml/element :enclosure {:url (str base-url "/" audio-dir "/" name)
                              :length (str size)
                              :type "audio/x-m4a"}))))

(defn feed [base-url files]
  (xml/element
   :rss {:version "2.0" :xmlns/itunes itunes-ns}
   (xml/element
    :channel {}
    (xml/element :title {} "tube-pod")
    (xml/element :link {} (str base-url "/" feed-file))
    (xml/element :description {} "Talks queued for offline listening.")
    (xml/element :language {} "en")
    (xml/element ::itunes/author {} "tube-pod")
    (xml/element ::itunes/explicit {} "false")
    (map #(episode base-url %) files))))

(defn write! [base-url]
  (let [fs (files)]
    (spit feed-file (xml/indent-str (feed base-url fs)))
    (count fs)))
