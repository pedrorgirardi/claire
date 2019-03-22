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
          (js/console.log (str "[Clojure Run] RUN COMMAND '" cmd-name "'"))

          (try
            (cmd *sys)
            (catch js/Error e
              (js/console.error (str "[Clojure Run] FAILED TO RUN COMMAND '" cmd-name "'") e))))]
    (vscode/commands.registerCommand cmd-name callback)))

(defn- register-text-editor-command [*sys cmd]
  (let [cmd-name (-> cmd meta :cmd)

        callback
        (fn [editor edit args]
          (js/console.log (str "[Clojure Run] RUN EDITOR COMMAND '" cmd-name "'"))

          (try
            (cmd *sys editor edit args)
            (catch js/Error e
              (js/console.error (str "[Clojure Run] FAILED TO RUN EDITOR COMMAND '" cmd-name "'") e))))]
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

(defn ^{:cmd "clojure-run.run"} run [*sys]
  (p/promise [resolve _]
             (let [config-path (when-let [path (root-path)]
                                 (path/join path "clojure-run.edn"))

                   config-exist? (when config-path
                                   (fs/file? config-path))

                   config (when config-exist?
                            (-> (io/slurp config-path)
                                (reader/read-string)))

                   runc (merge config (get @*sys :claire/run-configuration))

                   available-configurations (keys runc)]

               (p/let [picked-configuration (gui/show-quick-pick available-configurations {:placeHolder "Run..."})]
                 (when-let [{:keys [cmd args managed?]} (runc picked-configuration)]
                   (let [cmd (or cmd "clj")

                         args (or args [])

                         cwd (or (root-path) (os/tmpdir))

                         _ (-> (out *sys)
                               (log (str "Run '" picked-configuration "'...\n")
                                    (log-str-command cmd args)
                                    (log-str-cwd cwd))
                               (show-log))

                         ^js terminal (when-not managed?
                                        (vscode/window.createTerminal #js {:name picked-configuration
                                                                           :cwd cwd}))

                         _ (when terminal
                             (.sendText terminal (str cmd " " (str/join " " args)))
                             (.show terminal true)

                             (-> (out *sys)
                                 (log (str "See Terminal '" picked-configuration "'."))))

                         ^js process (when managed?
                                       (child-process/spawn cmd (clj->js args) #js {:cwd cwd}))]

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

                     (swap! *sys assoc :claire/program
                            (merge {:claire.program/name picked-configuration
                                    :claire.program/cmd cmd
                                    :claire.program/args args
                                    :claire.program/cwd cwd}

                                   (when terminal
                                     {:claire.program/terminal terminal})

                                   (when process
                                     {:claire.program/process process})))

                     (resolve nil)))))))

(defn ^{:cmd "clojure-run.stop"} stop [*sys]
  (let [^js process (get-in @*sys [:claire/program :claire.program/process])
        ^js terminal (get-in @*sys [:claire/program :claire.program/terminal])
        runc-name (get-in @*sys [:claire/program :claire.program/name])]
    (if (or process terminal)
      (do
        (log (out *sys) "\nStopping program...\n")

        (if process
          (do
            ;; Show log if it's a managed process.
            (show-log (out *sys))
            (.kill process))
          (do
            (.dispose terminal)
            (log (out *sys) (str "Terminal '" runc-name "' was disposed.\n")))))
      (-> (out *sys)
          (log "No program is running.\n")
          (show-log)))))

(defn ^{:cmd "clojure-run.sendSelectionToProgram"} send-selection-to-program [*sys editor _ _]
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

(def *sys
  (atom {}))

(def default-config
  (let [deps '{:deps
               {org.clojure/clojure {:mvn/version "1.10.0"}
                org.clojure/clojurescript {:mvn/version "1.10.520"}}}

        deps (str "'" (pr-str deps) "'")]
    {"Leiningen REPL"
     {:cmd "lein"
      :args ["repl"]}

     "Sandbox: Clojure REPL"
     {:cmd "clj"
      :args ["-Sdeps" deps]}

     "Sandbox: ClojureScript - Browser REPL"
     {:cmd "clj"
      :args ["-Sdeps" deps "-m" "cljs.main" "--repl-env" "browser"]}

     "Sandbox: ClojureScript - Node.js REPL"
     {:cmd "clj"
      :args ["-Sdeps" deps "-m" "cljs.main" "--repl-env" "node"]}}))

(defn activate [^js context]
  (let [output-channel (vscode/window.createOutputChannel "Clojure Run")]

    (dispose context
             (register-command *sys #'run)
             (register-command *sys #'stop)
             (register-text-editor-command *sys #'send-selection-to-program))

    (reset! *sys {:claire/output-channel output-channel
                  :claire/run-configuration default-config})

    (-> (out *sys)
        (log "Clojure Run is active.\n")))

  nil)

(defn deactivate []
  nil)

