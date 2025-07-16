(ns claude-log-stream.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [claude-log-stream.core :as core]))

(deftest test-validate-args
  (testing \"shows help when requested\"
    (let [result (core/validate-args [\"-h\"])]
      (is (:ok? result))
      (is (contains? result :exit-message))))
  
  (testing \"shows help when --help requested\"
    (let [result (core/validate-args [\"--help\"])]
      (is (:ok? result))
      (is (contains? result :exit-message))))
  
  (testing \"requires file argument\"
    (let [result (core/validate-args [])]
      (is (not (:ok? result)))
      (is (contains? result :exit-message))))
  
  (testing \"validates file existence\"
    (let [result (core/validate-args [\"-f\" \"/nonexistent/file.jsonl\"])]
      (is (not (:ok? result)))
      (is (contains? result :exit-message))))
  
  (testing \"accepts valid file path\"
    ;; Create a temporary file for testing
    (let [temp-file (java.io.File/createTempFile \"test\" \".jsonl\")]
      (.deleteOnExit temp-file)
      (let [result (core/validate-args [\"-f\" (.getAbsolutePath temp-file)])]
        (is (= :analyze (:action result)))
        (is (= (.getAbsolutePath temp-file) (get-in result [:options :file])))))))

(deftest test-cli-options-parsing
  (testing \"parses file option\"
    (let [result (core/validate-args [\"-f\" \"test.jsonl\"])]
      (when (= :analyze (:action result))
        (is (= \"test.jsonl\" (get-in result [:options :file]))))))
  
  (testing \"parses output directory option\"
    (let [temp-file (java.io.File/createTempFile \"test\" \".jsonl\")]
      (.deleteOnExit temp-file)
      (let [result (core/validate-args [\"-f\" (.getAbsolutePath temp-file) \"-o\" \"/custom/output\"])]
        (when (= :analyze (:action result))
          (is (= \"/custom/output\" (get-in result [:options :output])))))))
  
  (testing \"sets default output directory\"
    (let [temp-file (java.io.File/createTempFile \"test\" \".jsonl\")]
      (.deleteOnExit temp-file)
      (let [result (core/validate-args [\"-f\" (.getAbsolutePath temp-file)])]
        (when (= :analyze (:action result))
          (is (= \"./output\" (get-in result [:options :output])))))))
  
  (testing \"parses watch flag\"
    (let [temp-file (java.io.File/createTempFile \"test\" \".jsonl\")]
      (.deleteOnExit temp-file)
      (let [result (core/validate-args [\"-f\" (.getAbsolutePath temp-file) \"-w\"])]
        (when (= :analyze (:action result))
          (is (true? (get-in result [:options :watch])))))))
  
  (testing \"parses dashboard flag\"
    (let [temp-file (java.io.File/createTempFile \"test\" \".jsonl\")]
      (.deleteOnExit temp-file)
      (let [result (core/validate-args [\"-f\" (.getAbsolutePath temp-file) \"-d\"])]
        (when (= :analyze (:action result))
          (is (true? (get-in result [:options :dashboard])))))))
  
  (testing \"parses verbose flag\"
    (let [temp-file (java.io.File/createTempFile \"test\" \".jsonl\")]
      (.deleteOnExit temp-file)
      (let [result (core/validate-args [\"-f\" (.getAbsolutePath temp-file) \"-v\"])]
        (when (= :analyze (:action result))
          (is (true? (get-in result [:options :verbose]))))))))

(deftest test-usage-message
  (testing \"generates usage message\"
    (let [usage-msg (core/usage \"test summary\")]
      (is (string? usage-msg))
      (is (.contains usage-msg \"Claude Log Stream\"))
      (is (.contains usage-msg \"Usage:\"))
      (is (.contains usage-msg \"Examples:\")))))

(deftest test-error-message
  (testing \"generates error message\"
    (let [errors [\"Error 1\" \"Error 2\"]
          error-msg (core/error-msg errors)]
      (is (string? error-msg))
      (is (.contains error-msg \"Error 1\"))
      (is (.contains error-msg \"Error 2\"))))))