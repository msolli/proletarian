(ns proletarian.test.config)

(def jdbc-url (or (System/getenv "DATABASE_URL")
                  "jdbc:postgresql://localhost/proletarian?user=proletarian&password="))
