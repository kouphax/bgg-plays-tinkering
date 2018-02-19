(ns playas.core
  (require [net.cgrand.enlive-html :as html]
           [clojure.core.memoize :as memo]
           [compojure.core :refer :all]
           [compojure.route :as route]
           [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
           [ring.middleware.json :as middleware]
           [ring.util.response :refer  [response]])
  (use [clojure.pprint]))

(defn fetch-url [url]
  (html/html-resource (java.net.URL. url)))

(defn retrieve-page-of-plays-xml
  ([username]
   (retrieve-page-of-plays-xml username 1))
  ([username page]
    (fetch-url (str "https://boardgamegeek.com/xmlapi2/plays?username=" username "&page=" page))))

(defn read-total-plays [xml]
  (-> (html/select xml [:plays]) first :attrs :total Integer.))

(defn calculate-total-pages [xml]
  (int (Math/ceil (/ (read-total-plays xml) 100))))


(defn log [level & args]
  (let [timestamp (.format (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:sssZ") (java.util.Date.))
        message   (clojure.string/join " " args)]
    (println timestamp level message)))

(defn retrieve-all-plays [username]
  (let [first-page-xml       (retrieve-page-of-plays-xml username)
        first-page-plays     (html/select first-page-xml [:play])
        total-pages          (calculate-total-pages first-page-xml)
        remaining-page-plays (for [page (range 2 (inc total-pages))
                                   :let [page-xml (retrieve-page-of-plays-xml username page)]]
                               (html/select page-xml [:play]))]
    (log :warn "retrieving all plays for" username)
    (flatten [first-page-plays remaining-page-plays])))

(def ten-minutes-in-millis 600000)

(def all-plays-cache
  (memo/ttl retrieve-all-plays :ttl/threshold ten-minutes-in-millis))

(defn to-date [date-string]
  (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd") date-string))

(defn xml-to-player [xml]
  (let [attrs (-> xml :attrs)]
    { :name                     (attrs :name)
      :first-time-playing-game? (= "1" (attrs :new))
      :winner?                  (= "1" (attrs :win))
      :score                    (-> attrs :score Integer.) }))

(defn xml-to-play [xml]
  (let [attrs   (-> xml :attrs)
        game    (-> xml (html/select [:item]) first :attrs)
        players (-> xml (html/select [:players :player]))]
    { :date     (-> attrs :date to-date)
      :location (-> attrs :location)
      :game     { :id   (game :objectid)
                  :name (game :name) }
      :players  (map xml-to-player players)}))

(def user "kouphax")

(def parsed-plays
  (map xml-to-play (all-plays-cache "kouphax")))

(def london-group (set ["James" "Bill" "Chris" "Nick"]))
(def family-group (set ["Emma" "James" "Ollie" "Nate"]))

(def groups { :london london-group
              :family family-group })

(defn is-group-play? [play group-players]
  (let [all-plays-players   (->> play :players (map :name) set)
        plays-group-players (clojure.set/intersection all-plays-players group-players)]
    (> (count plays-group-players) 1)))

(defn group-plays [group]
  (filter #(is-group-play? % group) parsed-plays))

(defn plays-per-player [plays]
  (let [play-stats-fn (fn [[player-name plays]]
                        (let [total-plays (->> plays count)
                              wins        (->> plays (filter :winner?) count)]
                          { :name        player-name
                            :total-plays total-plays
                            :wins        wins
                            :win-pct     (-> (/ wins total-plays) (* 100) int) }))]
    (->> plays
         (mapcat :players)
         (group-by :name)
         (map play-stats-fn))))

(defn calculate-avg-win-pct-for-all-players [player-stats]
  (let [win-pcts (map :win-pct player-stats)]
    (int (/ (apply + win-pcts) (count win-pcts)))))

(defn calculate-player-elo [wins loses constant avg-win-pct-for-all-players]
  (/ (+ wins (* constant avg-win-pct-for-all-players)) (+ wins loses constant)))

(defn assoc-elo [all-player-stats]
  (let [avg-win-pct-for-all-players (calculate-avg-win-pct-for-all-players all-player-stats)
        elo-fn (fn [player-stats]
                 (let [wins (player-stats :wins)
                       loses (- (player-stats :total-plays) wins)]
                   (int (calculate-player-elo wins loses 20 avg-win-pct-for-all-players))))
        assoc-elo-fn (fn [player-stats]
                       (assoc player-stats :elo (elo-fn player-stats)))]
  (map assoc-elo-fn all-player-stats)))

;;;;; BROKEN BELOW SO FAR


;(assoc-elo (plays-per-player (group-plays (groups :family))))



;(pprint (->> plays-per-player-with-elo (sort-by :elo)))



(defroutes app-routes
  (GET "/" [] (response
                (assoc-elo (plays-per-player (group-plays (groups :family))))))
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      (middleware/wrap-json-body  {:keywords? true})
      (middleware/wrap-json-response)))
