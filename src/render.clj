(ns render
  (:require
   [config]
   [babashka.fs :as fs]
   [clojure.data.xml :as xml]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [hiccup2.core :as hiccup]
   [markdown.core :as md]
   [selmer.parser :as selmer])
  (:import (java.time.format DateTimeFormatter)))


(xml/alias-uri 'atom "http://www.w3.org/2005/Atom")


(def bodies (atom {}))

(defn html-file [file]
  (str/replace file ".md" ".html"))

(def post-template
  "<h1>{{title}}</h1>
{{body | safe }}
<p><i>Published: {{date}}</i></p>
")


(defn markdown->html [file]
  (println "Processing markdown for file:" (str file))
  (-> file
      slurp
      md/md-to-html-string))


(defn assoc-post-link
  [{:keys [file] :as post}]
  (assoc post
         :link
         (->> (str/replace file ".md" ".html")
              (str config/+posts-dir+ "/"))))

(defn get-posts
  []
  (->> config/+posts-edn+
       slurp
       (format "[%s]")
       edn/read-string
       (map assoc-post-link)
       (sort-by :date (comp - compare))))

(defn gen-post!
  [{:keys [file base-html out-dir title date]}]
  (let [cache-file (fs/file config/+work-dir+ (html-file file))
        markdown-file (fs/file config/+posts-dir+ file)
        stale? (seq (fs/modified-since cache-file
                                       [markdown-file
                                        config/+posts-edn+
                                        config/+templates-dir+
                                        "src/render.clj"]))
        body (if stale?
               (let [body (markdown->html markdown-file)]
                 (spit cache-file body)
                 body)
               (slurp cache-file))
        _ (swap! bodies assoc file body)
        body (selmer/render post-template {:body body
                                           :title title
                                           :date date})
        html (selmer/render base-html
                            {:title title
                             :body body})
        html-file (str/replace file ".md" ".html")]
    (spit (fs/file out-dir html-file) html)))




(defn post-links [{:keys [posts]}]
  [:div {:style "width: 600px;"}
   [:h1 "Archive"]
   [:ul.index
    (for [{:keys [title link date preview]} posts
          :when (not preview)]
      [:li [:span
            [:a {:href link}
             title]
            " - "
            date]])]])


;; Generate index page with last 3 posts
(defn index [{:keys [posts]}]
  (for [{:keys [file link title date preview]} (take 3 posts)
        :when (not preview)]
    [:div
     [:h1 [:a {:href link}
           title]]
     (get @bodies file)
     [:p [:i "Published: " date]]]))


(defn rfc-3339-now []
  (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssxxx")
        now (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)]
    (.format now fmt)))

(defn rfc-3339 [yyyy-MM-dd]
  (let [in-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd")
        local-date (java.time.LocalDate/parse yyyy-MM-dd in-fmt)
        fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssxxx")
        now (java.time.ZonedDateTime/of (.atTime local-date 23 59 59) java.time.ZoneOffset/UTC)]
    (.format now fmt)))

(defn atom-feed
  ;; validate at https://validator.w3.org/feed/check.cgi
  [posts]
  (-> (xml/sexp-as-element
       [::atom/feed
        {:xmlns "http://www.w3.org/2005/Atom"}
        [::atom/title "Hakahaka"]
        [::atom/link {:href (str config/+blog-root+ "/atom.xml") :rel "self"}]
        [::atom/link {:href config/+blog-root+}]
        [::atom/updated (rfc-3339-now)]
        [::atom/id config/+blog-root+]
        [::atom/author
         [::atom/name "Amar Mehta"]]
        (for [{:keys [title date file link preview]} posts
              :when (not preview)]
          [::atom/entry
           [::atom/id link]
           [::atom/link {:href link}]
           [::atom/title title]
           [::atom/updated (rfc-3339 date)]
           [::atom/content {:type "html"}
            [:-cdata (get @bodies file)]]])])
      xml/indent-str))

(defn render!
  ([] (render! {}))
  ([{:keys [watch?]
     :or {watch? false}}]
   (let [base-html (slurp config/+base-html+)
         posts (get-posts)]

     (fs/create-dirs config/+out-dir+)
     (fs/create-dirs (fs/file config/+work-dir+))

     ;; generate posts
     (doseq [post posts]
       (-> post
           (merge {:out-dir config/+out-dir+
                   :base-html base-html
                   :watch watch?})
           (gen-post!)))

     ;; Generate archive page
     (let [links (post-links {:posts posts})]
       (spit (fs/file config/+blog-dir+ "archive.html")
             (selmer/render base-html
                            {:skip-archive true
                             :watch watch?
                             :body (hiccup/html links)})))

     ;; generate index page
     (spit (fs/file config/+blog-dir+ "index.html")
           (selmer/render base-html
                          {:watch watch?
                           :body (hiccup/html {:escape-strings? false}
                                              (index {:posts posts}))}))

     ;; Generate atom feeds
     (spit (fs/file config/+blog-dir+ "atom.xml") (atom-feed posts))
     (spit (fs/file config/+blog-dir+ "planetclojure.xml")
           (atom-feed (filter
                       (fn [post]
                         (some (:categories post) ["clojure" "clojurescript"]))
                       posts))))))
