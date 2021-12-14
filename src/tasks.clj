(ns tasks
  (:require
   [babashka.fs :as fs]
   [babashka.pods :as pods]
   [ring]
   [config]
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [render]
   [org.httpkit.server :as server]
   [selmer.parser :as selmer]))

(pods/load-pod 'org.babashka/filewatcher "0.0.1")
(require '[pod.babashka.filewatcher :as filewatcher])

(defn parse-opts
  ([opts] (parse-opts opts nil))
  ([opts opts-def]
   (let [[cmds opts] (split-with #(not (str/starts-with? % ":")) opts)]
     (reduce
      (fn [opts [arg-name arg-val]]
        (let [k (keyword (subs arg-name 1))
              od (k opts-def)
              v ((or (:parse-fn od) identity) arg-val)]
          (if-let [c (:collect-fn od)]
            (update opts k c v)
            (assoc opts k v))))
      {:cmds cmds}
      (partition 2 opts)))))

(def opts (parse-opts *command-line-args*))

(def post-template
  (str/triml "
{:title {{title | safe }}
 :file {{file | safe }}
 :categories {{categories | safe }}
 :date {{date | safe }}}\n"))

(defn now
  []
  (pr-str
   (.format (java.time.LocalDate/now)
            (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))))

(defn gen-file-name
  [s]
  (-> s
      (str/lower-case)
      (str/replace #" " "-")
      (str ".md")))

(defn new
  []
  (let [{:keys [file title]} opts]
    (assert title "Must give title")
    (let [file (or file (gen-file-name title))
          post-file (fs/file "posts" file)]
      (when-not (fs/exists? post-file)
        (spit (fs/file "posts" file) "TODO: write blog post")
        (spit (fs/file "posts.edn")
              (selmer/render post-template
                             {:title (pr-str title)
                              :file (pr-str file)
                              :date (now)
                              :categories #{"clojure"}})
              :append true)))))

(defn render!
  ([]
   (render/render!))
  ([{:keys [path]}]
   (render/render! {:watch? true
                    :path path})))

(defn publish!
  []
  (fs/delete-tree "../xfthhxk.github.io/blog")
  (fs/copy-tree "public/blog" "../xfthhxk.github.io/blog")
  (shell/sh "git" "add" "."  :dir "../xfthhxk.github.io")
  (shell/sh "git" "commit" "-m'blog updated'"  :dir "../xfthhxk.github.io")
  (shell/sh "git" "push" :dir "../xfthhxk.github.io"))


;;----------------------------------------------------------------------
;; Server
;;----------------------------------------------------------------------
(defn routes
  [{:keys [request-method uri] :as _request}]
  (case request-method
    :head  (ring/head-response uri {:root config/+public-dir+})
    (ring/file-response uri {:root config/+public-dir+})))

(defn start-server!
  []
  (let [opts {:port (or (some-> (System/getenv "PORT")
                                Integer/parseInt)
                        8080)}
        watch-dirs [config/+posts-dir+ config/+templates-dir+]]

    (server/run-server #'routes opts)
    (println "server started " (pr-str opts))
    (println "watching directories " (pr-str watch-dirs))

    (doseq [dir watch-dirs]
      (filewatcher/watch dir render!))

    ;; block on promise otherwise process will exit
    @(promise)))
