.PHONY: test clean jar examples.db.install examples.db.uninstall examples.db.recreate

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

dist/proletarian.jar: src/proletarian/* deps.edn
	clojure -X:depstar:jar :jar dist/proletarian.jar :version '"$(VERSION)"'

jar: dist/proletarian.jar

deploy: dist/proletarian.jar
	git tag -a v$(VERSION) -m "Version $(VERSION)"
	git push origin v$(VERSION)
	clojure -X:deploy :artifact '"dist/proletarian.jar"'

mvn.install: dist/proletarian.jar
	mvn install:install-file -Dfile=dist/proletarian.jar -DpomFile=pom.xml

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
	rm -rf dist