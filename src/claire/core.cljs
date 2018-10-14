(ns claire.core
  (:require
   ["vscode" :as vscode]
   ["os" :as os]
   ["path" :as path]
   ["child_process" :as child-process]

   [claire.gui :as gui]

   [oops.core :refer [oget]]
   [kitchen-async.promise :as p]
   [cljfmt.core :as cljfmt]
   [cljs-node-io.fs :as fs]
   [cljs-node-io.core :as io]

   [clojure.string :as str]
   [cljs.reader :as reader]))

(defn- register-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)

        callback
        (fn []
          (js/console.log (str "[Claire] RUN COMMAND '" cmd-name "'"))

          (try
            (cmd *sys)
            (catch js/Error e
              (js/console.error (str "[Claire] FAILED TO RUN COMMAND '" cmd-name "'") e))))]
    (vscode/commands.registerCommand cmd-name callback)))

(defn- register-text-editor-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)

        callback
        (fn [editor edit args]
          (js/console.log (str "[Claire] RUN EDITOR COMMAND '" cmd-name "'"))

          (try
            (cmd *sys editor edit args)
            (catch js/Error e
              (js/console.error (str "[Claire] FAILED TO RUN EDITOR COMMAND '" cmd-name "'") e))))]
    (vscode/commands.registerTextEditorCommand cmd-name callback)))

(defn register-disposable [^js context ^js disposable]
  (-> (.-subscriptions context)
      (.push disposable)))

(defn with-dispose [^js context & disposables]
  (doseq [^js disposable disposables]
    (register-disposable context disposable)))

(defn root-path []
  (some-> (oget vscode "workspace.workspaceFolders")
          ;; The first entry corresponds to the value of rootPath.
          (first)
          (oget "uri.fsPath")))

(defn out [*sys]
  (get @*sys :claire/output-channel))

(defn show-log [out]
  (.show out true)

  out)

(defn log [out & values]
  (doseq [v values]
    (.appendLine out v))

  out)

(defn log-str-cwd [cwd]
  (str "Working directory\n\t" cwd "\n"))

(defn log-str-program [command args]
  (str "Program\n\t" command " "
       (when (seq args)
         (clj->js args))
       "\n"))

(defn ^{:cmd "claire.run"} launch [*sys]
  (p/promise [resolve _]
    (let [run-configuration-keys (keys (get @*sys :claire/run-configuration))]
      (p/let [run-configuration-key (gui/show-quick-pick run-configuration-keys {:placeHolder "Run..."})]
        (when-let [{:keys [run args] :or {run :clojure}} (get-in @*sys [:claire/run-configuration run-configuration-key])]
          (let [^js output-channel (get @*sys :claire/output-channel)

                command (case run
                          :clojure "clojure"
                          :lein "lein"
                          ;; `clojure` is the default command.
                          "clojure")

                args (or args [])

                cwd (or (root-path) (os/tmpdir))

                _ (.appendLine output-channel "Lauching program, please wait...\n")
                _ (.appendLine output-channel (log-str-program command args))
                _ (.appendLine output-channel (log-str-cwd cwd))
                _ (.show output-channel true)

                process (child-process/spawn command (clj->js args) #js {:cwd cwd})

                _ (.on (.-stdout process) "data"
                       (fn [data]
                         (let [output-channel (get @*sys :claire/output-channel)]
                           (.appendLine output-channel data)
                           (.show output-channel true))))

                _ (.on (.-stderr process) "data"
                       (fn [data]
                         (let [output-channel (get @*sys :claire/output-channel)]
                           (.appendLine output-channel data)
                           (.show output-channel true))))

                _ (.on process "close"
                       (fn [code]
                         (let [output-channel (get @*sys :claire/output-channel)]
                           (swap! *sys dissoc :claire/program)

                           (.appendLine output-channel (str "\nProgram exited with code " code ".\n"))
                           (.show output-channel true))))]

            (swap! *sys assoc :claire/program {:claire.program/command command
                                               :claire.program/args args
                                               :claire.program/cwd cwd
                                               :claire.program/process process})

            (resolve nil)))))))

(defn ^{:cmd "claire.stop"} kill [*sys]
  (when-let [^js process (get-in @*sys [:claire/program :claire.program/process])]
    (-> (out *sys)
        (log "\nStopping program..."))

    (.kill process)))

(defn ^{:cmd "claire.evalSelection"} send-selection-to-repl [*sys editor _ _]
  (if-let [^js process (get-in @*sys [:claire/program :claire.program/process])]
    (let [^js output-channel (get @*sys :claire/output-channel)
          ^js document (oget editor "document")
          ^js selection (oget editor "selection")
          ^js range (vscode/Range. (oget selection "start") (oget selection "end"))

          selected-text (.getText document range)]

      (-> (out *sys)
          (log "\nEvaluating...\n")
          (show-log))

      (.write (.-stdin process) (str selected-text "\n") "utf-8"))
    (-> (out *sys)
        (log "No program is running.\n")
        (show-log))))

(defn ^{:cmd "claire.clearOutput"} clear-output [*sys]
  (let [^js output-channel (get @*sys :claire/output-channel)]
    (.clear output-channel)))

(defn ^{:cmd "claire.info"} info [*sys]
  (let [^js output-channel (get @*sys :claire/output-channel)

        {:keys [:claire.program/command
                :claire.program/args
                :claire.program/cwd
                :claire.program/process]} (get @*sys :claire/program)]

    (if process
      (-> (out *sys)
          (log (log-str-program command args)
               (log-str-cwd cwd))
          (show-log))
      (-> (out *sys)
          (log "No program is running.\n")
          (show-log)))))

(def *sys
  (atom {}))

(def default-config
  (let [deps '{:deps
               {org.clojure/clojure {:mvn/version "1.10.0-RC1"}}

               :aliases
               {:cljs
                {:extra-deps
                 {org.clojure/clojurescript {:mvn/version "1.10.339"}}}}}]
    {"Clojure"
     {}

     "Leiningen"
     {:run :lein
      :args ["repl"]}

     "Playground: Clojure REPL"
     {:args ["-Sdeps" (pr-str deps)]}

     "Playground: ClojureScript - Browser REPL"
     {:args ["-Sdeps" (pr-str deps) "-A:cljs" "-m" "cljs.main" "--repl-env" "browser"]}

     "Playground: ClojureScript - Node.js REPL"
     {:args ["-Sdeps" (pr-str deps) "-A:cljs" "-m" "cljs.main" "--repl-env" "node"]}}))

(defn activate [^js context]
  (let [root-path (root-path)

        config-path (when root-path
                      (path/join root-path ".claire.edn"))

        config-exist? (when config-path
                        (fs/file? config-path))

        config (when config-exist?
                 (-> (io/slurp config-path)
                     (reader/read-string)))

        output-channel (vscode/window.createOutputChannel "Claire")]

    (with-dispose context
      (register-command *sys #'launch)
      (register-command *sys #'kill)
      (register-text-editor-command *sys #'send-selection-to-repl)
      (register-text-editor-command *sys #'clear-output)
      (register-text-editor-command *sys #'info))

    (reset! *sys {:claire/output-channel output-channel
                  :claire/run-configuration (merge config default-config)})

    (.appendLine output-channel "Claire is active.\n"))

  nil)

(defn deactivate []
  nil)

