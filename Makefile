.PHONY: test clean jar examples.db.install examples.db.uninstall examples.db.recreate

VERSION_PREFIX := 1.0.
VERSION_SUFFIX := -alpha
GIT_COMMIT_COUNT := $(shell git rev-list --count HEAD)
VERSION := $(VERSION_PREFIX)$(GIT_COMMIT_COUNT)$(VERSION_SUFFIX)

repl:
	clj -M:dev:examples

test:
	clojure -M:test -m kaocha.runner --config-file test/tests.edn

examples.db.install:
	DATABASE_NAME=proletarian ./database/install.sh

examples.db.uninstall:
	DATABASE_NAME=proletarian ./database/uninstall.sh

examples.db.recreate: examples.db.uninstall examples.db.install

target/proletarian.jar: src/proletarian/* deps.edn
	clojure -T:build jar

jar: target/proletarian.jar

deploy: target/proletarian.jar
	clojure -T:build deploy

mvn.install: target/proletarian.jar
	clojure -T:build install

# Run the cljdoc process for reviewing the docs on
# localhost:8000/d/msolli/proletarian before publishing.
# Run `make cljdoc.import` in another terminal window to import the project.
cljdoc.run:
	docker pull cljdoc/cljdoc
	docker run --rm --publish 8000:8000 --volume "$(HOME)/.m2:/root/.m2" --volume /tmp/cljdoc:/app/data cljdoc/cljdoc

# Import the docs to cljdoc
cljdoc.import: mvn.install CHANGELOG.md README.md doc/*
	docker run --rm \
	--volume "$(shell pwd):/git-repo" \
	--volume "$(HOME)/.m2:/root/.m2" \
	--volume /tmp/cljdoc:/app/data \
	--entrypoint clojure cljdoc/cljdoc \
	-A:cli ingest \
	--project msolli/proletarian \
	--version $(VERSION) \
	--git /git-repo \
	--rev $(shell git rev-parse HEAD)

clean:
	rm -rf target
