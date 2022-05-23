# Changelog

## 1.0.65-alpha - 2022-05-23

* Add on-polling-error option (#b4b8360)
* Add failed-job-fn option for handling failed jobs (#9d50f2a)
* Enqueue jobs in the future (#9d3f1d2)

## 1.0.54-alpha - 2021-12-02

### Changed

* [BREAKING] Removed `proletarian.job/retry-strategy` multimethod and added `:proletarian/retry-strategy-fn` option for
  `proletarian.worker/create-queue-worker` (#22a86fd)
* Made logger function 2-arity (#1df500e)
* Updated Postgres JDBC driver (#cb8d442)
* Moved to the MIT license (see https://www.juxt.pro/blog/prefer-mit)

### Fixed

* Handle interrupted status also when non-InterruptedException exception is caught (#c83dc53)
* Don't stop Queue Worker on SQLTransientException (#0bf9dff)

### Added

* Logging instructions in README (#fdcc4e9, #dd7156c)

## 1.0.41-alpha - 2021-06-10

### Added

* Extracted `proletarian.worker/process-next-job!` function. This function is part of the internal machinery of the
  Proletarian worker, but is being exposed as a public function for use in testing scenarios and in the REPL.

## 1.0.38-alpha - 2021-06-04

### Changed

* `handle-job!` is now a function that must be passed as second argument to `create-queue-worker`. The
  `proletarian.job/handle-job!` multimethod has been replaced by this.

## 1.0.32-alpha - 2021-03-20

### Added

* Lots of documentation updates.

### Changed

* The `:now-fn` option to `enqueue!` has been removed in favor of a `:clock` option, mirroring the `create-queue-worker`
  options.

## 1.0.21-alpha - 2021-02-07

Initial public release.
