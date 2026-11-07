(ns hc.client.schedule.platform
  "Honest platform detection for the schedule workspace.

  `:ui/platform` in the workbench is input mode (`:desktop`/`:mobile`) and is
  not an operating system.  This namespace reads the browser user agent only
  to decide which background-execution claim is honest.  It never promises a
  wakeup the platform does not provide."
  (:require [clojure.string :as str]
            [hc.client.schedule.model :as model]))

(defn- navigator []
  (when (exists? js/navigator) js/navigator))

(defn user-agent
  []
  (or (some-> (navigator) .-userAgent) ""))

(defn- ipad-desktop-ua?
  "iPadOS 13+ reports a desktop Safari user agent.  A Mac with more than one
  touch point is treated as iPad."
  []
  (let [nav (navigator)]
    (and nav
         (str/includes? (str (.-platform nav)) "Mac")
         (> (or (.-maxTouchPoints nav) 0) 1))))

(defn detect
  "Return `:ios`, `:android`, `:desktop`, or `:unknown`."
  []
  (let [ua (user-agent)]
    (cond
      (or (re-find #"iPhone|iPad|iPod" ua)
          (ipad-desktop-ua?))
      :ios

      (re-find #"Android" ua)
      :android

      (str/blank? ua)
      :unknown

      :else
      :desktop)))

(defn policy
  [kind]
  (model/platform-policy kind))
