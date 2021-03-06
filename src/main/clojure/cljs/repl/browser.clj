;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns cljs.repl.browser
  (:refer-clojure :exclude [loaded-libs])
  (:require [clojure.java.io :as io]
            [clojure.java.browse :as browse]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [cljs.util :as util]
            [cljs.env :as env]
            [cljs.closure :as cljsc]
            [cljs.repl :as repl]
            [cljs.cli :as cli]
            [cljs.repl.server :as server]
            [cljs.stacktrace :as st]
            [cljs.analyzer :as ana]
            [cljs.build.api :as build])
  (:import [java.util.concurrent Executors]))

(def ^:dynamic browser-state nil)
(def ^:dynamic ordering nil)
(def ^:dynamic es nil)

(def ext->mime-type
  {".html" "text/html"
   ".css" "text/css"

   ".jpg" "image/jpeg"
   ".png" "image/png"
   ".gif" "image/gif"
   ".svg" "image/svg+xml"

   ".js" "text/javascript"
   ".json" "application/json"
   ".clj" "text/x-clojure"
   ".cljs" "text/x-clojure"
   ".cljc" "text/x-clojure"
   ".edn" "text/x-clojure"
   ".map" "application/json"})

(def mime-type->encoding
  {"text/html" "UTF-8"
   "text/css" "UTF-8"
   "image/jpeg" "ISO-8859-1"
   "image/png" "ISO-8859-1"
   "image/gif" "ISO-8859-1"
   "image/svg+xml" "UTF-8"
   "text/javascript" "UTF-8"
   "text/x-clojure" "UTF-8"
   "application/json" "UTF-8"})

(defn- set-return-value-fn
  "Save the return value function which will be called when the next
  return value is received."
  [f]
  (swap! browser-state (fn [old] (assoc old :return-value-fn f))))

(defn send-for-eval
  "Given a form and a return value function, send the form to the
  browser for evaluation. The return value function will be called
  when the return value is received."
  ([form return-value-fn]
    (send-for-eval @(server/connection) form return-value-fn))
  ([conn form return-value-fn]
    (set-return-value-fn return-value-fn)
    (server/send-and-close conn 200 form "text/javascript")))

(defn- return-value
  "Called by the server when a return value is received."
  [val]
  (when-let [f (:return-value-fn @browser-state)]
    (f val)))

(defn repl-client-js []
  (slurp (:client-js @browser-state)))

(defn send-repl-client-page
  [request conn opts]
  (server/send-and-close conn 200
    (str "<html><head><meta charset=\"UTF-8\"></head><body>
          <script type=\"text/javascript\">"
         (repl-client-js)
         "</script>"
         "<script type=\"text/javascript\">
          clojure.browser.repl.client.start(\"http://" (-> request :headers :host) "\");
          </script>"
         "</body></html>")
    "text/html"))

(defn default-index [output-to]
  (str
    "<!DOCTYPE html><html>"
    "<head>"
    "<meta charset=\"UTF-8\">"
    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" >"
    "<link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"cljs-logo-icon-32.png\"/>"
    "</head>"
    "<body>"
    "<div id=\"app\">"
    "<link href=\"https://fonts.googleapis.com/css?family=Open+Sans\" rel=\"stylesheet\">"
    "<style>"
    "body { padding: 28px; margin: auto; max-width: 42em; "
    "font-family: \"Open Sans\", sans-serif; }"
    "code, pre { color: #4165a2; font-size: 17px; white-space: pre-wrap; }"
    "</style>"
    "<center><img src=\"cljs-logo.svg\" style=\"width: 200px; height: 200px; margin: 15px;\"/></center>"
    "<p>Welcome to the default <code>index.html</code> provided by the ClojureScript Browser REPL.</p>"
    "<p>This page provides the evaluation environment for your Browser REPL and application.</p>"
    "<p>You can quickly validate the connection by typing <code>(js/alert&nbsp;\"Hello&nbsp;CLJS!\")</code> into the "
    "ClojureScript REPL that launched this page.</p><p>You can easily use your own HTML file to host your application "
    "and REPL by providing your own <code>index.html</code> in the directory that you launched this REPL from.</p>"
    "<p>Start with this template:</p>"
    "<pre>"
    "&lt;!DOCTYPE html&gt;\n"
    "&lt;html&gt;\n"
    "  &lt;head&gt;\n"
    "    &lt;meta charset=\"UTF-8\"&gt;\n"
    "  &lt;/head&gt;\n"
    "  &lt;body&gt;\n"
    "    &lt;script src=\"" output-to "\" type=\"text/javascript\"&gt;&lt;/script&gt;\n"
    "  &lt;/body&gt;\n"
    "&lt;/html&gt;\n"
    "</pre>"
    "</div></div>"
    "<script src=\"" output-to "\"></script>"
    "</body></html>"))

(defn send-static
  [{path :path :as request} conn
   {:keys [static-dir output-to output-dir host port gzip?] :or {output-dir "out"} :as opts}]
  (let [output-dir (when-not (.isAbsolute (io/file output-dir)) output-dir)]
    (if (and static-dir (not= "/favicon.ico" path))
      (let [path (if (= "/" path) "/index.html" path)
            local-path
            (cond->
              (seq (for [x (if (string? static-dir) [static-dir] static-dir)
                         :when (.exists (io/file (str x path)))]
                     (str x path)))
              (complement nil?) first)
            local-path
            (if (nil? local-path)
              (cond
                (re-find #".jar" path)
                (io/resource (second (string/split path #".jar!/")))
                (string/includes? path (System/getProperty "user.dir"))
                (io/file (string/replace path (str (System/getProperty "user.dir") "/") ""))
                (#{"/cljs-logo-icon-32.png" "/cljs-logo.svg"} path)
                (io/resource (subs path 1))
                :else nil)
              local-path)]
        (cond
          local-path
          (if-let [ext (some #(if (.endsWith path %) %) (keys ext->mime-type))]
            (let [mime-type (ext->mime-type ext "text/plain")
                  encoding (mime-type->encoding mime-type "UTF-8")]
              (server/send-and-close conn 200 (slurp local-path :encoding encoding)
                mime-type encoding (and gzip? (= "text/javascript" mime-type))))
            (server/send-and-close conn 200 (slurp local-path) "text/plain"))
          ;; "/index.html" doesn't exist, provide our own
          (= path "/index.html")
          (server/send-and-close conn 200
            (default-index (or output-to (str output-dir "/main.js")))
            "text/html" "UTF-8")
          (= path (cond->> "/main.js" output-dir (str "/" output-dir )))
          (let [closure-defines (-> `{clojure.browser.repl/HOST ~host
                                      clojure.browser.repl/PORT ~port}
                                  cljsc/normalize-closure-defines
                                  json/write-str)]
            (server/send-and-close conn 200
              (str "var CLOSURE_UNCOMPILED_DEFINES = " closure-defines ";\n"
                   "var CLOSURE_NO_DEPS = true;\n"
                   "document.write('<script src=\"" output-dir "/goog/base.js\"></script>');\n"
                   "document.write('<script src=\"" output-dir "/goog/deps.js\"></script>');\n"
                   (when (.exists (io/file output-dir "cljs_deps.js"))
                     "document.write('<script src=\"" output-dir "/cljs_deps.js\"></script>');\n")
                   "document.write('<script src=\"" output-dir "/brepl_deps.js\"></script>');\n"
                   "document.write('<script>goog.require(\"clojure.browser.repl.preload\");</script>');\n")
              "text/javascript" "UTF-8"))
          :else (server/send-404 conn path)))
      (server/send-404 conn path))))

(server/dispatch-on :get
  (fn [{:keys [path]} _ _]
    (.startsWith path "/repl"))
  send-repl-client-page)

(server/dispatch-on :get
  (fn [{:keys [path]} _ _]
    (or (= path "/") (some #(.endsWith path %) (keys ext->mime-type))))
  send-static)

(defmulti handle-post (fn [m _ _ ] (:type m)))

(server/dispatch-on :post (constantly true) handle-post)

(defmethod handle-post :ready [_ conn _]
  (send-via es ordering (fn [_] {:expecting nil :fns {}}))
  (send-for-eval conn
    (binding [ana/*cljs-warnings*
              (assoc ana/*cljs-warnings*
                :undeclared-var false)]
      (cljsc/-compile
       '[(set! *print-fn* clojure.browser.repl/repl-print)
         (set! *print-err-fn* clojure.browser.repl/repl-print)
         (set! *print-newline* true)
         (when (pos? (count clojure.browser.repl/print-queue))
           (clojure.browser.repl/flush-print-queue!
             @clojure.browser.repl/xpc-connection))] {}))
    identity))

(defn add-in-order [{:keys [expecting fns]} order f]
  {:expecting (or expecting order)
   :fns (assoc fns order f)})

(defn run-in-order [{:keys [expecting fns]}]
  (loop [order expecting fns fns]
    (if-let [f (get fns order)]
      (do
        (f)
        (recur (inc order) (dissoc fns order)))
      {:expecting order :fns fns})))

(defn constrain-order
  "Elements to be printed in the REPL will arrive out of order. Ensure
  that they are printed in the correct order."
  [order f]
  (send-via es ordering add-in-order order f)
  (send-via es ordering run-in-order))

(defmethod handle-post :print [{:keys [content order]} conn _ ]
  (constrain-order order
    (fn []
      (print (read-string content))
      (.flush *out*)))
  (server/send-and-close conn 200 "ignore__"))

(defmethod handle-post :result [{:keys [content order]} conn _ ]
  (constrain-order order
    (fn []
      (return-value content)
      (server/set-connection conn))))

(defn browser-eval
  "Given a string of JavaScript, evaluate it in the browser and return a map representing the
   result of the evaluation. The map will contain the keys :type and :value. :type can be
   :success, :exception, or :error. :success means that the JavaScript was evaluated without
   exception and :value will contain the return value of the evaluation. :exception means that
   there was an exception in the browser while evaluating the JavaScript and :value will
   contain the error message. :error means that some other error has occured."
  [form]
  (let [return-value (promise)]
    (send-for-eval form
      (fn [val] (deliver return-value val)))
    (let [ret @return-value]
      (try
        (read-string ret)
        (catch Exception e
          {:status :error
           :value (str "Could not read return value: " ret)})))))

(defn load-javascript
  "Accepts a REPL environment, a list of namespaces, and a URL for a
  JavaScript file which contains the implementation for the list of
  namespaces. Will load the JavaScript file into the REPL environment
  if any of the namespaces have not already been loaded from the
  ClojureScript REPL."
  [repl-env provides url]
  (browser-eval (slurp url)))

(defn serve [{:keys [host port output-dir] :as opts}]
  (binding [ordering (agent {:expecting nil :fns {}})
            es (Executors/newFixedThreadPool 16)
            server/state (atom {:socket nil :connection nil :promised-conn nil})]
    (server/start
      (merge opts
        {:static-dir (cond-> ["." "out/"] output-dir (conj output-dir))
         :gzip? true}))))

;; =============================================================================
;; BrowserEnv

(defn setup [{:keys [working-dir launch-browser] :as repl-env} {:keys [output-dir] :as opts}]
  (binding [browser-state (:browser-state repl-env)
            ordering (:ordering repl-env)
            es (:es repl-env)
            server/state (:server-state repl-env)]
    (swap! browser-state
      (fn [old]
        (assoc old :client-js
          (cljsc/create-client-js-file
            {:optimizations :simple
             :output-dir working-dir}
            (io/file working-dir "brepl_client.js")))))
    ;; TODO: this could be cleaner if compiling forms resulted in a
    ;; :output-to file with the result of compiling those forms - David
    (when (and output-dir (not (.exists (io/file output-dir "clojure" "browser" "repl" "preload.js"))))
      (let [target (io/file output-dir "brepl_deps.js")]
        (util/mkdirs target)
        (spit target
          (build/build
            '[(require '[clojure.browser.repl.preload])]
            (merge (select-keys opts cljsc/known-opts)
              {:opts-cache "brepl_opts.edn"})))))
    (server/start repl-env)
    (when launch-browser
      (browse/browse-url
        (str "http://" (:host repl-env) ":" (:port repl-env) "?rel=" (System/currentTimeMillis))))))

(defrecord BrowserEnv []
  repl/IJavaScriptEnv
  (-setup [this opts]
    (setup this opts))
  (-evaluate [this _ _ js]
    (binding [browser-state (:browser-state this)
              ordering (:ordering this)
              es (:es this)
              server/state (:server-state this)]
      (browser-eval js)))
  (-load [this provides url]
    (load-javascript this provides url))
  (-tear-down [this]
    (binding [server/state (:server-state this)]
      (server/stop))
    (.shutdownNow (:es this)))
  repl/IReplEnvOptions
  (-repl-options [this]
    {:browser-repl true
     :repl-requires
     '[[clojure.browser.repl] [clojure.browser.repl.preload]]
     :cljs.cli/commands
     {:groups {::repl {:desc "browser REPL options"}}
      :init
      {["-H" "--host"]
       {:group ::repl :fn #(assoc-in %1 [:repl-env-options :host] %2)
        :arg "address"
        :doc "Address to bind"}
       ["-p" "--port"]
       {:group ::repl :fn #(assoc-in %1 [:repl-env-options :port] (Integer/parseInt %2))
        :arg "number"
        :doc "Port to bind"}}}})
  repl/IParseStacktrace
  (-parse-stacktrace [this st err opts]
    (st/parse-stacktrace this st err opts))
  repl/IGetError
  (-get-error [this e env opts]
    (edn/read-string
      (repl/evaluate-form this env "<cljs repl>"
        `(when ~e
           (pr-str
             {:ua-product (clojure.browser.repl/get-ua-product)
              :value (str ~e)
              :stacktrace (.-stack ~e)}))))))

(defn repl-env*
  [{:keys [output-dir host port] :or {host "localhost" port 9000} :as opts}]
  (merge (BrowserEnv.)
    {:host host
     :port port
     :launch-browser true
     :working-dir (->> [".repl" (util/clojurescript-version)]
                       (remove empty?) (string/join "-"))
     :static-dir (cond-> ["." "out/"] output-dir (conj output-dir))
     :preloaded-libs []
     :src "src/"
     :browser-state (atom {:return-value-fn nil
                           :client-js nil})
     :ordering (agent {:expecting nil :fns {}})
     :es (Executors/newFixedThreadPool 16)
     :server-state
     (atom
       {:socket nil
        :connection nil
        :promised-conn nil})}
    opts))

(defn repl-env
  "Create a browser-connected REPL environment.

  Options:

  port:           The port on which the REPL server will run. Defaults to 9000.
  launch-browser: A Boolean indicating whether a browser should be automatically
                  launched connecting back to the terminal REPL. Defaults to true.
  working-dir:    The directory where the compiled REPL client JavaScript will
                  be stored. Defaults to \".repl\" with a ClojureScript version
                  suffix, eg. \".repl-0.0-2138\".
  static-dir:     List of directories to search for static content. Defaults to
                  [\".\" \"out/\"].
  src:            The source directory containing user-defined cljs files. Used to
                  support reflection. Defaults to \"src/\".
  "
  [& {:as opts}]
  (repl-env* opts))

(defn -main [& args]
  (apply cli/main repl-env args))

(comment

  (require '[cljs.repl :as repl])
  (require '[cljs.repl.browser :as browser])
  (def env (browser/repl-env))
  (repl/repl env)
  ;; simulate the browser with curl
  ;; curl -v -d "ready" http://127.0.0.1:9000
  ClojureScript:> (+ 1 1)
  ;; curl -v -d "2" http://127.0.0.1:9000

  )
