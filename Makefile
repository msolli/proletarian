.PHONY: test clean examples.db.install examples.db.uninstall examples.db.recreate

VERSION_PREFIX := 1.0.
VERSION_SUFFIX := -alpha
GIT_COMMIT_COUNT := $(shell git rev-list --count HEAD)
VERSION := $(VERSION_PREFIX)$(GIT_COMMIT_COUNT)$(VERSION_SUFFIX)

test:
	clojure -M:test -m kaocha.runner --config-file test/tests.edn

examples.db.install:
	DATABASE_NAME=proletarian ./database/install.sh

examples.db.uninstall:
	DATABASE_NAME=proletarian ./database/uninstall.sh

examples.db.recreate: examples.db.uninstall examples.db.install

dist/proletarian.jar: src/proletarian/* pom.xml
	clojure -X:depstar:jar :jar dist/proletarian.jar :version '"$(VERSION)"'

deploy: dist/proletarian.jar
	clojure -X:deploy :artifact '"dist/proletarian.jar"'
	git tag -a v$(VERSION) -m "Version $(VERSION)"
	git push origin v$(VERSION)

clean:
	rm -rf dist