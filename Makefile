.PHONY: test examples.db.install examples.db.uninstall examples.db.recreate

test:
	clojure -M:test -m kaocha.runner --config-file test/tests.edn

examples.db.install:
	DATABASE_NAME=proletarian ./database/install.sh

examples.db.uninstall:
	DATABASE_NAME=proletarian ./database/uninstall.sh

examples.db.recreate: examples.db.uninstall examples.db.install
