(ns muguet.test-utils
  (:require [clojure.test :refer :all]
            [diehard.core :as dh]
            [muguet.api :as muga]
            [muguet.internals.commands :as cmd]))

(defn blocking-fetch-result
  [version id]
  (dh/with-retry
    {:retry-if (fn [ret _ex] (= ::muga/pending (::muga/command-status ret)))
     :delay-ms 10
     :max-duration-ms 5000}
    (cmd/fetch-command-result version id)))