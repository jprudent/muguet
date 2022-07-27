(ns muguet.learn-interceptor-test
  (:require [clojure.test :refer :all]
            [exoscale.interceptor :as sut]))

(deftest error-with-error-handler-test
  (let [res (sut/execute {:x 1}
                         [{:enter (fn [ctx] (update ctx :x inc))}])]
    (is (= 2 (:x res))))
  (let [res (sut/execute {:x 1}
                         [{:enter (fn [ctx] (update ctx :x inc))}
                          {:leave (fn [ctx] (update ctx :x dec))}])]
    (is (= 1 (:x res))))
  (let [res (sut/execute {:x 1}
                         [{:error (fn [ctx] (assoc ctx :x "alarma!!!"))}])]
    (is (= 1 (:x res))))
  (let [res (sut/execute {:x 1}
                         [{:error (fn [ctx error] (assoc ctx :x error))}
                          {:enter (fn [ctx] (throw (ex-info "alarma!" ctx)))}])]
    (is (= "alarma!" (ex-message (:x res)))))
  (let [res (sut/execute {:x 1}
                         [{:error (fn [ctx error] (assoc ctx :x error))}
                          {:enter (fn [ctx] (assoc ctx ::sut/error "alarma!"))}])]
    (is (= "alarma!" (:x res))))
  (let [res (sut/execute {:x 1}
                         [{:error (fn [_ctx error] error)}
                          {:enter (fn [ctx] (assoc ctx ::sut/error "alarma!"))}])]
    (is (= "alarma!" res))))