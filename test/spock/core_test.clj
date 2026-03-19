(ns spock.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [spock.shader.core :as shader]
            [spock.renderer.vulkan :as vk]
            [spock.game.core :as game]))

(deftest make-vulkan-renderer-test
  (testing "VulkanRenderer can be constructed"
    (let [r (vk/make-vulkan-renderer)]
      (is (some? r))
      (is (= [0.1 0.12 0.18 1.0] (spock.renderer.core/get-clear-color r))))))

(deftest set-clear-color-test
  (testing "set-clear-color! mutates state"
    (let [r (vk/make-vulkan-renderer)]
      (spock.renderer.core/set-clear-color! r [1.0 0.0 0.5 1.0])
      (is (= [1.0 0.0 0.5 1.0] (spock.renderer.core/get-clear-color r))))))

(deftest make-game-test
  (testing "make-game constructs a game with defaults"
    (let [g (game/make-game "test")]
      (is (= "test" (:title g)))
      (is (= 1280 (:width g)))
      (is (= 720 (:height g))))))
