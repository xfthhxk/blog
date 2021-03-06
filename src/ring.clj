;; NB. Most of the code is from https://github.com/ring-clojure/ring/blob/master/ring-core/src/ring/util/response.clj
(ns ring
  "Functions for generating and augmenting response maps."
  (:require [clojure.string :as str])
  (:import [java.io File]))

(defn response
  "Returns a skeletal Ring response with the given body, status of 200, and no
  headers."
  [body]
  {:status  200
   :headers {}
   :body    body})


(defn header
  "Returns an updated Ring response with the specified header added."
  [resp name value]
  (assoc-in resp [:headers name] (str value)))


(defn- canonical-path ^String [^File file]
  (str (.getCanonicalPath file)
       (when (.isDirectory file) File/separatorChar)))

(defn- safe-path? [^String root ^String path]
  (.startsWith (canonical-path (File. root path))
               (canonical-path (File. root))))

(defn- directory-transversal?
  "Check if a path contains '..'."
  [^String path]
  (-> (str/split path #"/|\\")
      (set)
      (contains? "..")))

(defn- find-file-named [^File dir ^String filename]
  (let [path (File. dir filename)]
    (when (.isFile path)
      path)))

(defn- find-file-starting-with [^File dir ^String prefix]
  (first
   (filter
    #(.startsWith (.toLowerCase (.getName ^File %)) prefix)
    (.listFiles dir))))

(defn- find-index-file
  "Search the directory for an index file."
  [^File dir]
  (or (find-file-named dir "index.html")
      (find-file-named dir "index.htm")
      (find-file-starting-with dir "index.")))

(defn- safely-find-file [^String path opts]
  (if-let [^String root (:root opts)]
    (when (or (safe-path? root path)
            (and (:allow-symlinks? opts) (not (directory-transversal? path))))
      (File. root path))
    (File. path)))

(defn- find-file [^String path opts]
  (when-let [^File file (safely-find-file path opts)]
    (cond
      (.isDirectory file)
        (and (:index-files? opts true) (find-index-file file))
      (.exists file)
        file)))

(defn last-modified-date
  "Returns the last modified date for a file, rounded down to the nearest
  second."
  {:added "1.2"}
  [^File file]
  (-> (.lastModified file)
      (/ 1000) (long) (* 1000)
      (java.util.Date.)))

(def file-ext->content-type
  {"js" "application/javascript"
   "html" "text/html"
   "css" "text/css"})

(defn guess-content-type
  [^File file]
  (let [filename (.getName file)
        idx (str/last-index-of  filename ".")
        ext (subs filename (inc idx))]
    (file-ext->content-type ext)))


(defn- file-data [^File file]
  {:content-type (guess-content-type file)
   :content        file
   :content-length (.length file)
   :last-modified (last-modified-date file)})

(defn- content-length [resp len]
  (if len
    (header resp "Content-Length" len)
    resp))

(defn last-modified
  [resp date]
  (if date
    (header resp "last-modified" date)
    resp))

(defn content-type
  [resp ct]
  (if ct
    (header resp "content-type" ct)
    resp))


(defn file-response
  "Returns a Ring response to serve a static file, or nil if an appropriate
  file does not exist.
  Options:
    :root            - take the filepath relative to this root path
    :index-files?    - look for index.* files in directories (defaults to true)
    :allow-symlinks? - allow symlinks that lead to paths outside the root path
                       (defaults to false)"
  ([filepath]
   (file-response filepath {}))
  ([filepath options]
   (when-let [file (find-file filepath options)]
     (let [data (file-data file)]
       (-> (response (:content data))
           (content-length (:content-length data))
           (content-type (:content-type data))
           (last-modified (:last-modified data)))))))


(defn head-response
  ([filepath] (head-response filepath {}))
  ([filepath options]
   (when-let [file (find-file filepath options)]
     (let [data (file-data file)]
       (-> {:status 204
            :headers {"etag" (.lastModified file)}}
           (content-length (:content-length data))
           (content-type (:content-type data))
           (last-modified (:last-modified data)))))))
