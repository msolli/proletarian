(ns proletarian.job-id-strategies-test
  (:require [clojure.test :refer [deftest is testing]]
            [proletarian.job-id-strategies :as sut]
            [proletarian.protocols :as proto])
  (:import (java.util UUID)))

(deftest ->postgresql-uuid-strategy-test
  (testing "PostgreSQL UUID strategy"
    (testing "generates a UUID"
      (let [strategy (sut/->postgresql-uuid-strategy)
            id (proto/generate-id strategy)]
        (is (uuid? id) "generates a UUID")
        (is (some? id) "is not nil")))

    (testing "generates unique IDs on each call"
      (let [strategy (sut/->postgresql-uuid-strategy)
            id1 (proto/generate-id strategy)
            id2 (proto/generate-id strategy)]
        (is (not= id1 id2) "generates different UUIDs each time")))

    (testing "can encode and decode IDs"
      (let [strategy (sut/->postgresql-uuid-strategy)
            original-id (proto/generate-id strategy)
            encoded (proto/encode-id strategy original-id)
            decoded (proto/decode-id strategy encoded)]
        (is (= original-id decoded) "decoded ID matches original")
        (is (uuid? decoded) "decoded value is a UUID")))

    (testing "encoding preserves the UUID"
      (let [strategy (sut/->postgresql-uuid-strategy)
            test-uuid (UUID/randomUUID)
            encoded (proto/encode-id strategy test-uuid)]
        ;; For PostgreSQL, encoding should just return the UUID as-is
        (is (= test-uuid encoded) "encoded UUID is the same as input")))

    (testing "decode handles nil gracefully"
      (let [strategy (sut/->postgresql-uuid-strategy)
            decoded (proto/decode-id strategy nil)]
        (is (nil? decoded) "returns nil when decoding nil")))))


(deftest ->mysql-uuid-strategy-test
  (testing "MySQL UUID strategy"
    (testing "generates a UUID"
      (let [strategy (sut/->mysql-uuid-strategy)
            id (proto/generate-id strategy)]
        (is (uuid? id) "generates a UUID")
        (is (some? id) "is not nil"))))

  (testing "generates unique IDs on each call"
    (let [strategy (sut/->mysql-uuid-strategy)
          id1 (proto/generate-id strategy)
          id2 (proto/generate-id strategy)]
      (is (not= id1 id2) "generates different UUIDs each time")))

  (testing "encoding produces a byte array"
    (let [strategy (sut/->mysql-uuid-strategy)
          test-uuid (UUID/randomUUID)
          encoded (proto/encode-id strategy test-uuid)]
      (is (bytes? encoded) "encoded value is be a byte array")
      (is (= 16 (alength encoded)) "byte array is 16 bytes long")))

  (testing "can encode and decode IDs"
    (let [strategy (sut/->mysql-uuid-strategy)
          original-id (proto/generate-id strategy)
          encoded (proto/encode-id strategy original-id)
          decoded (proto/decode-id strategy encoded)]
      (is (= original-id decoded) "decoded ID matches original")
      (is (uuid? decoded) "decoded value is a UUID")))

  (testing "correctly encodes and decodes a specific UUID"
    (let [strategy (sut/->mysql-uuid-strategy)
          test-uuid (UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
          encoded (proto/encode-id strategy test-uuid)
          decoded (proto/decode-id strategy encoded)]
      (is (= test-uuid decoded) "correctly roundtrips a specific UUID")
      (is (bytes? encoded) "encoded is a byte array")))

  (testing "decode handles nil gracefully"
    (let [strategy (sut/->mysql-uuid-strategy)
          decoded (proto/decode-id strategy nil)]
      (is (nil? decoded) "returns nil when decoding nil"))))

(deftest ->db-generated-strategy-test
  (testing "DB-generated strategy"
    (testing "returns nil from generate-id"
      (let [strategy (sut/->db-generated-strategy)
            id (proto/generate-id strategy)]
        (is (nil? id) "returns nil to signal DB generates the ID"))))

  (testing "encode-id is a no-op (returns nil)"
    (let [strategy (sut/->db-generated-strategy)
          encoded (proto/encode-id strategy nil)]
      (is (nil? encoded) "returns nil since there's no ID to encode")))

  (testing "decode-id returns the database value as-is"
    (let [strategy (sut/->db-generated-strategy)
          db-id 12345
          decoded (proto/decode-id strategy db-id)]
      (is (= 12345 decoded) "returns the database ID unchanged")
      (is (number? decoded) "is a number")))

  (testing "handles different numeric types"
    (let [strategy (sut/->db-generated-strategy)]
      (is (= 42 (proto/decode-id strategy 42)) "handles int")
      (is (= 999999999999 (proto/decode-id strategy 999999999999)) "handles long"))))
