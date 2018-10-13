(ns claire.gui
  (:require
   ["vscode" :as vscode]))

(defn show-information-message [message]
  (vscode/window.showInformationMessage message))

(defn show-quick-pick [items & [options]]
  (vscode/window.showQuickPick (clj->js items)
                               (when options
                                 (clj->js options))))

(defn show-input-box [& [options]]
  (vscode/window.showInputBox (clj->js options)))