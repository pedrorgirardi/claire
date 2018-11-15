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

(defn dispose [^js context & disposables]
  (doseq [^js disposable disposables]
    (register-disposable context disposable)))

(defn root-path []
  (some-> (oget vscode "workspace.workspaceFolders")
          ;; The first entry corresponds to the value of rootPath.
          (first)
          (oget "uri.fsPath")))

(defn out [*sys]
  ^js (get @*sys :claire/output-channel))

(defn show-log [out]
  (.show ^js out true)

  ^js out)

(defn log [out & values]
  (doseq [v values]
    (.appendLine ^js out v))

  ^js out)

(defn log-str-cwd [cwd]
  (str "Working directory\n\t" cwd "\n"))

(defn log-str-command [command args]
  (str command " "
       (when (seq args)
         (str/join " " args))
       "\n"))

(defn ^{:cmd "claire.run"} run [*sys]
  (p/promise [resolve _]
    (let [config-path (when-let [path (root-path)]
                        (path/join path ".claire.edn"))

          config-exist? (when config-path
                          (fs/file? config-path))

          config (when config-exist?
                   (-> (io/slurp config-path)
                       (reader/read-string)))

          runc (merge config (get @*sys :claire/run-configuration))

          available-configurations (keys runc)]

      (p/let [picked-configuration (gui/show-quick-pick available-configurations {:placeHolder "Run..."})]
        (when-let [{:keys [run args managed?] :or {run :clojure}} (runc picked-configuration)]
          (let [command (case run
                          :clojure "clojure"
                          :lein "lein"
                          ;; `clojure` is the default command.
                          "clojure")

                args (or args [])

                cwd (or (root-path) (os/tmpdir))

                _ (-> (out *sys)
                      (log (str "Run '" picked-configuration "'...\n")
                           (log-str-command command args)
                           (log-str-cwd cwd))
                      (show-log))

                ^js terminal (when-not managed?
                               (vscode/window.createTerminal #js {:name picked-configuration
                                                                  :cwd cwd}))

                _ (when terminal
                    (.sendText terminal (str command " " (str/join " " args)))

                    (-> (out *sys)
                        (log (str "See Terminal '" picked-configuration "'."))))

                ^js process (when managed?
                              (child-process/spawn command (clj->js args) #js {:cwd cwd}))]

            (when process
              (.on (.-stdout process) "data"
                   (fn [data]
                     (-> (out *sys)
                         (log data))))

              (.on (.-stderr process) "data"
                   (fn [data]
                     (-> (out *sys)
                         (log data))))

              (.on process "close"
                   (fn [code]
                     (swap! *sys dissoc :claire/program)

                     (-> (out *sys)
                         (log (str "\nProgram exited with code " code ".\n"))))))

            (swap! *sys assoc :claire/program (merge {:claire.program/name picked-configuration
                                                      :claire.program/command command
                                                      :claire.program/args args
                                                      :claire.program/cwd cwd}

                                                     (when terminal
                                                       {:claire.program/terminal terminal})

                                                     (when process
                                                       {:claire.program/process process})))

            (resolve nil)))))))

(defn ^{:cmd "claire.stop"} stop [*sys]
  (let [^js process (get-in @*sys [:claire/program :claire.program/process])
        ^js terminal (get-in @*sys [:claire/program :claire.program/terminal])
        runc-name (get-in @*sys [:claire/program :claire.program/name])]
    (if (or process terminal)
      (do
        (-> (out *sys)
            (log "\nStopping program...\n")
            (show-log))
        (if process
          (.kill process)
          (do
            (.dispose terminal)
            (log (out *sys) (str "Terminal '" runc-name "' was disposed.\n")))))
      (-> (out *sys)
          (log "No program is running.\n")
          (show-log)))))

(defn ^{:cmd "claire.evalSelection"} eval-selection [*sys editor _ _]
  (let [^js process (get-in @*sys [:claire/program :claire.program/process])
        ^js terminal (get-in @*sys [:claire/program :claire.program/terminal])]
    (if (or process terminal)
      (let [^js document (oget editor "document")
            ^js selection (oget editor "selection")
            text (.getText document selection)]
        (if process
          (do
            (-> (out *sys)
                (log "\nEvaluating...\n")
                (show-log))
            (.write (.-stdin process) (str text "\n") "utf-8"))
          (do
            (.sendText terminal text)
            (.show terminal true))))
      (-> (out *sys)
          (log "No program is running.\n")
          (show-log)))))

(defn ^{:cmd "claire.clearOutput"} clear-output [*sys]
  (let [^js output-channel (get @*sys :claire/output-channel)]
    (.clear output-channel)))

(defn ^{:cmd "claire.info"} info [*sys]
  (let [{:keys [:claire.program/command
                :claire.program/args
                :claire.program/cwd
                :claire.program/process]} (get @*sys :claire/program)]
    (if process
      (-> (out *sys)
          (log (log-str-command command args)
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
                 {org.clojure/clojurescript {:mvn/version "1.10.339"}}}}}

        deps (str "'" (pr-str deps) "'")]
    {"Clojure"
     {}

     "Leiningen REPL"
     {:run :lein
      :args ["repl"]}

     "Playground: Clojure REPL"
     {:args ["-Sdeps" deps]}

     "Playground: ClojureScript - Browser REPL"
     {:args ["-Sdeps" deps "-A:cljs" "-m" "cljs.main" "--repl-env" "browser"]}

     "Playground: ClojureScript - Node.js REPL"
     {:args ["-Sdeps" deps "-A:cljs" "-m" "cljs.main" "--repl-env" "node"]}}))

(defn activate [^js context]
  (let [output-channel (vscode/window.createOutputChannel "Claire")]

    (dispose context
      (register-command *sys #'run)
      (register-command *sys #'stop)
      (register-text-editor-command *sys #'eval-selection)
      (register-text-editor-command *sys #'clear-output)
      (register-text-editor-command *sys #'info))

    (reset! *sys {:claire/output-channel output-channel
                  :claire/run-configuration default-config})

    (-> (out *sys)
        (log "Claire is active.\n")))

  nil)

(defn deactivate []
  nil)

